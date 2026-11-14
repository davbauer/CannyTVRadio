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

    @Volatile var preset = EqPreset.Flat
        set(value) {
            if (field != value) android.util.Log.i("CannyTVRadio", "sound: equalizer $value")
            field = value
            dirty = true
        }

    @Volatile private var dirty = true
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
            android.util.Log.i("CannyTVRadio", "sound: applied ${preset.name}")
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
            for (c in 0 until channels) out.putShort((limit(frame[c] * gain) * 32767f).toInt().toShort())
        }
        input.position(input.limit())
        out.flip()
    }

    override fun onFlush() {
        bands.forEach { ch -> ch.forEach { it.reset() } }
    }

    override fun onReset() {
        envelope = TARGET * TARGET
        gain = 1f
    }

    private fun rebuild() {
        dirty = false
        val g = preset.gains
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
