package com.cannytvradio.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Equalizer presets: gain in dB for 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz. Clearly audible on purpose. */
enum class EqPreset(val gains: FloatArray) {
    Flat(floatArrayOf(0f, 0f, 0f, 0f, 0f)),
    Bass(floatArrayOf(9f, 5f, 0f, 0f, 0f)),
    Club(floatArrayOf(10f, 6f, -3f, 2f, 5f)),
    Voice(floatArrayOf(-6f, -2f, 4f, 7f, 2f)),
    Pop(floatArrayOf(3f, 5f, 3f, -1f, 3f)),
    Rock(floatArrayOf(7f, 2f, -4f, 4f, 7f)),
    Jazz(floatArrayOf(5f, 2f, -2f, 2f, 5f)),
    Classical(floatArrayOf(4f, 0f, -1f, 2f, 6f)),
    Warm(floatArrayOf(4f, 3f, 0f, -3f, -7f)),
    Bright(floatArrayOf(-2f, 0f, 0f, 5f, 9f)),
    Loudness(floatArrayOf(8f, 3f, -1f, 3f, 6f)),
    Night(floatArrayOf(-9f, -3f, 2f, 3f, -4f)),
}

/** Band frequencies of the equalizer, for labels. */
val EQ_BANDS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

/** Equalizer choices: the presets plus three profiles of your own. */
object Eq {
    val CUSTOM = listOf("Custom1", "Custom2", "Custom3")
    val ALL: List<String> = EqPreset.entries.map { it.name } + CUSTOM
    const val RANGE = 12

    fun gains(id: String, customs: List<List<Int>>): FloatArray =
        EqPreset.entries.firstOrNull { it.name == id }?.gains
            ?: CUSTOM.indexOf(id).takeIf { it >= 0 }?.let { i -> customs.getOrNull(i)?.map { it.toFloat() }?.toFloatArray() }
            ?: EqPreset.Flat.gains
}

/**
 * The app's own sound processing, the same on every TV:
 * a 5-band equalizer, then volume leveling so every station plays equally loud.
 *
 * Leveling measures loudness over about 1.5 s, lowers quickly (0.3 s) and raises slowly (3 s),
 * stays within ±12 dB and ignores silence, so quiet passages don't pump up noise.
 * A soft limiter keeps peaks from clipping.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var normalize = true
        set(value) {
            if (field != value) android.util.Log.i("CannyTVRadio", "sound: leveling ${if (value) "on" else "off"}")
            field = value
        }

    /** Gain in dB per band ([EQ_BANDS]); applied from the next audio block on. */
    @Volatile var gains: FloatArray = EqPreset.Flat.gains
        set(value) {
            if (!field.contentEquals(value)) android.util.Log.i("CannyTVRadio", "sound: equalizer ${value.joinToString()}")
            field = value
            dirty = true
        }

    @Volatile private var dirty = true

    /*
     * Music analysis for the screensaver, in steps of HOP frames (about 11 ms): how energetic the
     * music is compared to the last seconds, and beats (sudden jumps in bass or overall energy).
     * Each step is stored with its position in the stream, so the screen can show exactly the
     * step you are hearing, even though audio is processed about half a second ahead.
     */
    private val hopEnd = LongArray(HISTORY)
    private val hopEnergy = FloatArray(HISTORY)
    private val hopBeat = FloatArray(HISTORY)
    @Volatile private var head = -1
    private val onsetAt = LongArray(64)
    /** Beats found so far; [onset] gives each one's position in the stream. */
    @Volatile var onsetTotal = 0L
        private set
    /** Sample rate of what is playing, for turning stream positions into time. */
    val sampleRate: Int get() = analysisRate

    /** Stream position (in frames, see [sampleRate]) where beat number [index] was found. */
    fun onset(index: Long): Long = onsetAt[(index % onsetAt.size).toInt()]
    private var lastLog = 0L
    @Volatile private var analysisRate = 0
    private var position = 0L // frames since the stream started (or since the last flush)
    private var hopCount = 0
    private var hopE = 0f
    private var hopB = 0f
    private var low1 = 0f
    private var low2 = 0f
    private var lowK = 0f
    private var longE = 0f
    private var shortE = 0f
    private var midE = 0f
    private var midB = 0f
    private var beat = 0f
    private var sinceBeat = 1f
    private var kLong = 0f
    private var kShort = 0f
    private var kMid = 0f
    private var kBeat = 0f
    private var hopSeconds = 0f

    /**
     * Energy (0..1, about 0.5 for the song's usual level) and beat (1 right on a beat, fading
     * within a quarter second) of the audio at [positionMs], the player's current position,
     * written into [out]. Without a matching position it uses audio from about half a second ago.
     */
    fun pulse(positionMs: Long, out: FloatArray) {
        out[0] = 0f
        out[1] = 0f
        val h = head
        val r = analysisRate
        if (h < 0 || r == 0) return
        val newest = hopEnd[h]
        var want = positionMs * r / 1000
        val matched = want <= newest + r / 10 && want >= newest - 3L * r
        if (!matched) want = newest - r * 45L / 100
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLog > 20_000) {
            lastLog = now
            android.util.Log.i("CannyTVRadio", "sound: visuals ${if (matched) "follow the position" else "use a fixed delay"}, audio ahead by ${(newest - want) * 1000 / r} ms")
        }
        var i = h
        var prev = Long.MAX_VALUE
        var found = h
        for (step in 0 until HISTORY) {
            val f = hopEnd[i]
            if (f > prev) break // older than the current stream
            found = i
            if (f <= want) break
            prev = f
            i = (i - 1 + HISTORY) % HISTORY
        }
        out[0] = hopEnergy[found]
        out[1] = hopBeat[found]
    }

    private fun endHop() {
        val e = hopE / HOP
        val b = hopB / HOP
        hopE = 0f
        hopB = 0f
        hopCount = 0
        position += HOP
        // Beats: this step against the last third of a second, in the bass and overall.
        val fluxB = if (midB > 1e-8f) b / midB else 0f
        val fluxE = if (midE > 1e-8f) e / midE else 0f
        midB += (b - midB) * kMid
        midE += (e - midE) * kMid
        longE += (e - longE) * kLong
        shortE += (e - shortE) * kShort
        sinceBeat += hopSeconds
        beat *= kBeat
        val flux = max(fluxB, fluxE * 0.85f)
        if (flux > 1.45f && sinceBeat > 0.15f && e > 1e-6f) {
            beat = max(beat, ((flux - 1.15f) / 1.8f).coerceIn(0.4f, 1f))
            sinceBeat = 0f
            onsetAt[(onsetTotal % onsetAt.size).toInt()] = position
            onsetTotal++
        }
        val rel = if (longE > 1e-6f) sqrt(shortE / longE) else 0f
        val next = (head + 1) % HISTORY
        hopEnergy[next] = ((rel - 0.45f) / 1.1f).coerceIn(0f, 1f)
        hopBeat[next] = beat
        hopEnd[next] = position
        head = next
    }

    private var channels = 0
    private var rate = 0
    private var bands: Array<Array<Biquad>> = emptyArray()
    private var preamp = 1f

    private var envelope = TARGET * TARGET
    private var gain = 1f
    private var envCoef = 0f
    private var attack = 0f
    private var release = 0f

    override fun onConfigure(format: AudioFormat): AudioFormat {
        android.util.Log.i("CannyTVRadio", "sound: input $format")
        if (format.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(format)
        channels = format.channelCount
        rate = format.sampleRate
        hopSeconds = HOP.toFloat() / rate
        lowK = (1 - exp(-2 * PI * 150 / rate)).toFloat()
        kLong = (1 - exp(-hopSeconds / 3.0)).toFloat()
        kShort = (1 - exp(-hopSeconds / 0.1)).toFloat()
        kMid = (1 - exp(-hopSeconds / 0.33)).toFloat()
        kBeat = exp(-hopSeconds / 0.22).toFloat()
        analysisRate = rate
        envCoef = coef(1.5f)
        attack = coef(0.3f)
        release = coef(3f)
        dirty = true
        // Always active, so the settings can change while playing.
        return format
    }

    override fun queueInput(input: ByteBuffer) {
        if (dirty) {
            rebuild()
            android.util.Log.i("CannyTVRadio", "sound: applied ${gains.joinToString()}")
        }
        val size = input.remaining()
        val out = replaceOutputBuffer(size)
        val src = input.order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        val level = normalize
        while (src.remaining() >= channels * 2) {
            var power = 0f
            for (c in 0 until channels) {
                var x = src.short / 32768f * preamp
                for (b in bands[c]) x = b.process(x)
                frame[c] = x
                power += x * x
            }
            if (level) {
                power /= channels
                envelope += (power - envelope) * envCoef
                // Silence and near-silence keep the current gain instead of boosting noise.
                if (envelope > SILENCE) {
                    val wanted = (TARGET / sqrt(envelope)).coerceIn(MIN_GAIN, MAX_GAIN)
                    gain += (wanted - gain) * (if (wanted < gain) attack else release)
                }
            } else {
                gain += (1f - gain) * attack
            }
            var mono = 0f
            for (c in 0 until channels) {
                val y = limit(frame[c] * gain)
                mono += y
                out.putShort((y * 32767f).toInt().toShort())
            }
            mono /= channels
            // Two gentle low-pass steps keep the bass (kick drum) for beat detection.
            low1 += (mono - low1) * lowK
            low2 += (low1 - low2) * lowK
            hopE += mono * mono
            hopB += low2 * low2
            if (++hopCount == HOP) endHop()
        }
        input.position(input.limit())
        out.flip()
    }

    override fun onFlush() {
        bands.forEach { ch -> ch.forEach { it.reset() } }
        // A new stream (or a jump) starts counting from zero, like the player's position.
        position = 0L
        hopCount = 0
        hopE = 0f
        hopB = 0f
        low1 = 0f
        low2 = 0f
    }

    override fun onReset() {
        envelope = TARGET * TARGET
        gain = 1f
        head = -1
        longE = 0f; shortE = 0f; midE = 0f; midB = 0f; beat = 0f
    }

    private fun rebuild() {
        dirty = false
        val g = gains
        // Lower the input by part of the biggest boost so boosted bands don't clip.
        preamp = 10f.pow(-max(0f, g.max()) * 0.6f / 20f)
        bands = Array(channels) {
            arrayOf(
                Biquad.lowShelf(rate, 60f, g[0]),
                Biquad.peak(rate, 230f, g[1]),
                Biquad.peak(rate, 910f, g[2]),
                Biquad.peak(rate, 3600f, g[3]),
                Biquad.highShelf(rate, 14000f.coerceAtMost(rate * 0.45f), g[4]),
            ).filter { !it.flat }.toTypedArray()
        }
    }

    private fun coef(seconds: Float) = (1 - exp(-1.0 / (seconds * rate))).toFloat()

    /** Linear below 0.8, then bends smoothly towards 1.0 instead of clipping. */
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val bent = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -bent else bent
    }

    private companion object {
        const val TARGET = 0.12f // about -18 dBFS
        const val SILENCE = 1e-5f // about -50 dBFS
        const val MIN_GAIN = 0.25f // -12 dB
        const val MAX_GAIN = 4f // +12 dB
        const val HOP = 512 // about 11 ms
        const val HISTORY = 512 // about 6 s
    }
}

/** One second-order filter (RBJ audio EQ cookbook), direct form I. */
private class Biquad(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
    val flat: Boolean,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    companion object {
        private const val Q = 0.9

        fun peak(rate: Int, f: Float, db: Float): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * Q)
            return make(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a, db)
        }

        fun lowShelf(rate: Int, f: Float, db: Float): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val s = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s, db,
            )
        }

        fun highShelf(rate: Int, f: Float, db: Float): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val s = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s, db,
            )
        }

        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double, db: Float) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat(), db == 0f)
    }
}
