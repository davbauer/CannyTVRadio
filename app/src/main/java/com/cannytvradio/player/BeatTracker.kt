package com.cannytvradio.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the beat in music while it is processed, for visuals that move with it, and keeps working
 * when someone talks over the music (radio presenters, ads, jingles).
 *
 * Built on published methods:
 * - Onsets: log-compressed spectral flux in 17 bands, with the reference frame taken two hops back
 *   and maximum-filtered across neighbouring bands (SuperFlux, Böck & Widmer, DAFx 2013). Gliding
 *   pitch, as in speech and singing, then hardly counts as a new event.
 * - Peaks: picked against an adaptive threshold, the local mean of the onset curve plus an offset
 *   (Dixon, "Onset Detection Revisited", DAFx 2006).
 * - Tempo: autocorrelation of the onset curve, weighted by a tempo preference around 120 BPM
 *   (Ellis 2007), then a beat grid that predicts the next beat in real time and is nudged by every
 *   beat that lands on it (as in BTrack, Stark, Davies & Plumbley, DAFx 2009).
 * - Kick drum first: a steep filter keeps only the sub-bass below about 65 Hz, where a kick drum
 *   has its weight and a voice has almost nothing (even a low male voice starts around 85 Hz, and
 *   radio microphones usually cut below that). Its onsets drive both beats and tempo.
 * - Speech: in the spectrum the voice range counts least; speech carries its energy and its
 *   onsets there (Scheirer & Slaney 1997). Onsets off the beat grid are damped,
 *   so a "p" or "t" between beats doesn't flash. While someone talks over a steady beat, the grid
 *   keeps the pulse; when there is only talking, the visuals stay calm.
 *
 * Audio is processed about half a second before it is heard, so the tracker waits a few steps
 * before deciding whether something was a peak and then writes it back to where it happened.
 *
 * [push] runs on the audio thread; [read], [onset] and [onsetTotal] may be called from any thread.
 */
class BeatTracker(val rate: Int) {
    private val bandCount = EDGES.size - 1
    private val binBand = IntArray(FRAME / 2) { -1 }
    private val binsInBand = IntArray(bandCount)
    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / FRAME)).toFloat() }
    private val bitrev = IntArray(FRAME)
    private val cosT = FloatArray(FRAME / 2)
    private val sinT = FloatArray(FRAME / 2)
    private val re = FloatArray(FRAME)
    private val im = FloatArray(FRAME)
    private val samples = FloatArray(FRAME)
    private var sampleAt = 0
    private var inHop = 0
    private val energy = FloatArray(bandCount)
    // Sub-bass: a 6th-order Butterworth low-pass at 65 Hz, as three filter stages.
    private val sub = Array(3) { SubStage(rate, 65.0, SUB_Q[it]) }
    private var subEnergy = 0f
    private val subLogs = FloatArray(3)
    private val logs = Array(3) { FloatArray(bandCount) }

    // One entry per hop, in rings of HISTORY hops (about 6 s).
    private val odf = FloatArray(HISTORY)
    /** The kick-drum part of [odf] alone: stays periodic while someone talks over the music. */
    private val kickOdf = FloatArray(HISTORY)
    private val hopEnd = LongArray(HISTORY)
    private val hopEnergy = FloatArray(HISTORY)
    private val hopBeat = FloatArray(HISTORY)
    private val hopBass = FloatArray(HISTORY)
    private val hopTreble = FloatArray(HISTORY)
    private val hopPhase = FloatArray(HISTORY)
    @Volatile private var head = -1
    private var hops = 0L

    private val onsets = LongArray(64)
    /** Beats heard so far; [onset] gives each one's position. */
    @Volatile var onsetTotal = 0L
        private set

    private val hopSeconds = HOP.toFloat() / rate
    private val kBeat = exp(-hopSeconds / 0.22f)
    private val kLong = 1 - exp(-hopSeconds / 3f)
    private val kShort = 1 - exp(-hopSeconds / 0.1f)
    private val kSpeech = 1 - exp(-hopSeconds / 1.5f)
    private var longE = 0f
    private var shortE = 0f
    private var envelope = 0f
    private val peakLevel = FloatArray(2)
    /** The strongest recent peaks, fading over about 20 s: quiet talking in a music pause stays below them. */
    private val musicPeak = FloatArray(2)
    private val kMusicPeak = exp(-hopSeconds / 20f)
    private var lastOnset = -1000L
    private val kBassUp = 1 - exp(-hopSeconds / 0.01f)
    private val kBassDown = 1 - exp(-hopSeconds / 0.12f)
    private val kBassSlow = 1 - exp(-hopSeconds / 3f)
    private val kTreble = exp(-hopSeconds / 0.12f)
    private val kTreblePeak = exp(-hopSeconds / 4f)
    private var bassShort = 0f
    private var bassLong = 0f
    private var treble = 0f
    private var treblePeak = 0f
    private var speech = 0f

    // Beat grid, in hops.
    /** Beats per minute of the current grid, 0 while unknown. */
    @Volatile var bpm = 0f
        private set
    /** How sure the tracker is about a steady beat, 0..1. */
    @Volatile var confidence = 0f
        private set
    private var period = 0.0
    private var gridNext = 0.0
    // The best onset near the expected beat so far; chosen once the window around it has passed.
    private var pendingAt = 0L
    private var pendingStrength = 0f
    private var pendingScore = 0f
    private var lastOnGrid = -1000L
    private val acfBuffer = FloatArray(ACF_WINDOW)

    init {
        val bits = Integer.numberOfTrailingZeros(FRAME)
        for (i in 0 until FRAME) bitrev[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until FRAME / 2) {
            cosT[i] = cos(2 * PI * i / FRAME).toFloat()
            sinT[i] = -sin(2 * PI * i / FRAME).toFloat()
        }
        for (k in 1 until FRAME / 2) {
            val f = k.toFloat() * rate / FRAME
            for (b in 0 until bandCount) if (f >= EDGES[b] && f < EDGES[b + 1]) {
                binBand[k] = b
                binsInBand[b]++
            }
        }
        for (b in 0 until bandCount) if (binsInBand[b] == 0) binsInBand[b] = 1
    }

    /** Starts over for a new stream; found beats stay counted. */
    fun reset() {
        head = -1
        hops = 0
        inHop = 0
        samples.fill(0f)
        odf.fill(0f)
        kickOdf.fill(0f)
        acfSmooth.forEach { it.fill(0f) }
        hopBeat.fill(0f)
        longE = 0f; shortE = 0f; envelope = 0f; speech = 0f
        bassShort = 0f; bassLong = 0f; treble = 0f; treblePeak = 0f
        peakLevel.fill(0f)
        musicPeak.fill(0f)
        subEnergy = 0f
        subLogs.fill(0f)
        sub.forEach { it.reset() }
        lastOnset = -1000L
        period = 0.0; gridNext = 0.0; pendingScore = 0f; lastOnGrid = -1000L
        bpm = 0f; confidence = 0f
    }

    /** One mono sample of what is about to play. */
    fun push(sample: Float) {
        samples[sampleAt] = sample
        var low = sample
        for (stage in sub) low = stage.process(low)
        subEnergy += low * low
        sampleAt = (sampleAt + 1) and (FRAME - 1)
        if (++inHop == HOP) {
            inHop = 0
            hop()
        }
    }

    /**
     * What the music does at stream position [frame], written into [out] (see [OUT_ENERGY] and
     * the other indexes): energy (0..1, about 0.5 at the music's usual level), beat (1 right on a
     * beat, fading within a quarter second), bass, treble (both 0..1, quick) and beat phase (0 on
     * the beat rising to 1 just before the next; -1 while no steady beat is known). Returns false
     * when [frame] is not in the analysed range (then the values are from about half a second
     * before the newest audio).
     */
    fun read(frame: Long, out: FloatArray): Boolean {
        out.fill(0f)
        out[OUT_PHASE] = -1f
        val h = head
        if (h < 0) return false
        val newest = hopEnd[h]
        var want = frame
        val matched = want <= newest + rate / 10 && want >= newest - 3L * rate
        if (!matched) want = newest - rate * 45L / 100
        var i = h
        var prev = Long.MAX_VALUE
        var found = h
        for (step in 0 until HISTORY) {
            val f = hopEnd[i]
            if (f > prev || f == 0L) break
            found = i
            if (f <= want) break
            prev = f
            i = (i - 1 + HISTORY) % HISTORY
        }
        out[OUT_ENERGY] = hopEnergy[found]
        out[OUT_BEAT] = hopBeat[found]
        out[OUT_BASS] = hopBass[found]
        out[OUT_TREBLE] = hopTreble[found]
        out[OUT_PHASE] = hopPhase[found]
        return matched
    }

    /** Stream position, in frames, of beat number [index]. */
    fun onset(index: Long): Long = onsets[(index % onsets.size).toInt()]

    /** How far ahead of [frame] the analysis is, in frames. */
    fun lead(frame: Long): Long = if (head < 0) 0L else hopEnd[head] - frame

    private fun hop() {
        val n = hops++
        val slot = (n % HISTORY).toInt()

        // Spectrum of the last FRAME samples.
        for (i in 0 until FRAME) {
            val j = bitrev[i]
            re[j] = samples[(sampleAt + i) and (FRAME - 1)] * window[i]
            im[j] = 0f
        }
        fft()
        energy.fill(0f)
        for (k in 1 until FRAME / 2) {
            val b = binBand[k]
            if (b >= 0) energy[b] += re[k] * re[k] + im[k] * im[k]
        }

        // Log-compressed band magnitudes, and SuperFlux against two hops back.
        val cur = logs[(n % 3).toInt()]
        val ref = logs[((n + 1) % 3).toInt()]
        var music = 0f
        var flux = 0f
        var voice = 0f
        var all = 0f
        var high = 0f
        // Bass for the visuals: 30 to 110 Hz, and half of 110 to 160 Hz, where a low voice starts.
        // The spectrum sees a kick drum's attack at once; the sub-bass filter only its tail.
        val bassEnergy = energy[0] + energy[1] + 0.5f * energy[2]
        for (b in 0 until bandCount) {
            val magnitude = sqrt(energy[b] / binsInBand[b]) * (2f / FRAME)
            cur[b] = ln(1f + LOG_SCALE * magnitude)
            music += WEIGHT[b] * energy[b]
        }
        if (n >= 2) {
            for (b in 0 until bandCount) {
                var r = ref[b]
                if (b > 0 && ref[b - 1] > r) r = ref[b - 1]
                if (b < bandCount - 1 && ref[b + 1] > r) r = ref[b + 1]
                val d = cur[b] - r
                if (d > 0f) {
                    flux += WEIGHT[b] * d
                    all += d
                    if (b in VOICE) voice += d
                    if (b >= 12) high += d
                }
            }
        }
        // Sub-bass onset (the kick drum), log-compressed and against two hops back like the spectrum.
        val subLog = ln(1f + SUB_SCALE * sqrt(subEnergy / HOP))
        subEnergy = 0f
        val subFlux = if (n >= 2) max(0f, subLog - subLogs[((n + 1) % 3).toInt()]) else 0f
        subLogs[(n % 3).toInt()] = subLog
        odf[slot] = SUB_WEIGHT * subFlux + flux
        kickOdf[slot] = subFlux
        // Share of new sound in the voice range: high while someone talks.
        if (all > 0.05f) speech += (voice / all - speech) * kSpeech

        // Energy of the music, the voice range counting less.
        longE += (music - longE) * kLong
        shortE += (music - shortE) * kShort
        val rel = if (longE > 1e-9f) sqrt(shortE / longE) else 0f
        val talking = speechLevel() * (1f - confidence)

        // Bass (kick drum and bass line) against its usual level: rises at once, falls gently.
        bassShort += (bassEnergy - bassShort) * (if (bassEnergy > bassShort) kBassUp else kBassDown)
        bassLong += (bassEnergy - bassLong) * kBassSlow
        val bass = if (bassLong > 1e-9f) ((sqrt(bassShort / bassLong) - 0.5f) / 1.2f).coerceIn(0f, 1f) else 0f
        // Treble: new sound from hi-hats and cymbals, against the strongest of the last seconds.
        treblePeak = max(treblePeak * kTreblePeak, high)
        treble = max(treble * kTreble, if (treblePeak > 1e-4f) high / treblePeak else 0f)

        // This hop's slot: the beat envelope continues; peaks found below are written into it.
        envelope *= kBeat
        hopEnd[slot] = (n + 1) * HOP
        hopEnergy[slot] = ((rel - 0.45f) / 1.1f).coerceIn(0f, 1f) * (1f - 0.5f * talking)
        hopBeat[slot] = envelope
        hopBass[slot] = bass
        hopTreble[slot] = treble
        // The bass filter and the analysis window run about two hops behind the sound; the hops
        // before are not heard yet, so the rise is written there too.
        for (back in 1..2) {
            val s = idx(n - back)
            if (n >= back && hopBass[s] < bass) hopBass[s] = bass
        }
        if (n >= 1 && hopTreble[idx(n - 1)] < treble) hopTreble[idx(n - 1)] = treble
        // Where in the beat this hop is (0 on the beat, rising to 1 just before the next), or -1.
        hopPhase[slot] = if (period > 0.0 && confidence > 0.35f) {
            val p = 1.0 - (gridNext - n) / period
            (p - kotlin.math.floor(p)).toFloat()
        } else -1f
        head = slot

        if (n >= TEMPO_START && n % TEMPO_EVERY == 0L) updateTempo(n - LOOKAHEAD)
        pickPeak(n)
        predictBeats(n)
    }

    /**
     * How strong a peak [curve] has at hop [c], 0 when it is none: a local maximum over the
     * neighbouring hops, above the local mean plus an offset (Dixon 2006), and above a share of
     * the strongest recent peaks. [k] picks the state for this curve (0 full, 1 kick drum).
     */
    private fun peak(curve: FloatArray, c: Long, k: Int): Float {
        val oc = curve[idx(c)]
        for (j in -LOOKAHEAD..LOOKAHEAD) {
            if (j == 0) continue
            val o = curve[idx(c + j)]
            if (o > oc || (j < 0 && o == oc)) return 0f
        }
        var sum = 0f
        for (j in -MEAN_BACK..LOOKAHEAD) sum += curve[idx(c + j)]
        val mean = sum / (MEAN_BACK + LOOKAHEAD + 1)
        musicPeak[k] *= kMusicPeak
        val threshold = max(mean * 1.3f + 0.06f * peakLevel[k] + 0.02f / (1 + 2 * k), 0.25f * musicPeak[k])
        if (oc <= threshold) return 0f
        peakLevel[k] += (oc - peakLevel[k]) * 0.1f
        if (oc > musicPeak[k]) musicPeak[k] = oc
        return (0.4f + 0.6f * (oc - mean) / (peakLevel[k] + 1e-6f)).coerceIn(0.4f, 1f)
    }

    /** Decides whether the hop LOOKAHEAD steps back was a beat, now that the following hops are known. */
    private fun pickPeak(n: Long) {
        val c = n - LOOKAHEAD
        if (c < 30) return
        // The full curve, and the kick drum alone (which talking can't cover up).
        val kick = peak(kickOdf, c, 1)
        var strength = max(peak(odf, c, 0), kick)
        if (strength == 0f) return

        if (period > 0.0 && confidence > 0.25f) {
            val tolerance = period * GRID_TOLERANCE
            val distance = abs(c - gridNext)
            if (distance <= tolerance) {
                // Near the expected beat: only a candidate. The best one wins when the window has
                // passed, so a syllable just before the beat can't take the place of the kick.
                val score = strength * (if (kick > 0f) 1.4f else 1f) * (1f - 0.5f * (distance / tolerance).toFloat())
                if (score > pendingScore) {
                    pendingAt = c
                    pendingStrength = strength
                    pendingScore = score
                }
                return
            }
            // Just after a beat that was already decided: part of it, not a new one.
            if (abs(c - (gridNext - period)) <= tolerance) return
            // Between the beats: with a clear grid this is almost certainly talking.
            strength *= 1f - 0.9f * (confidence * 2f).coerceAtMost(1f)
        }
        if (c - lastOnset < MIN_GAP) return
        strength = gated(strength)
        if (strength < 0.22f) return
        lastOnset = c
        emit(c, n, strength, real = true)
    }

    /** Less reaction while no steady beat is known, and even less while someone talks. */
    private fun gated(strength: Float): Float {
        var s = strength
        // No steady beat found (yet): flashes stay faint; energy, bass and treble keep it lively.
        if (confidence < 0.45f) {
            val x = confidence / 0.45f
            s *= 0.08f + 0.92f * x * x * x
        }
        return s * (0.4f + 0.6f * max(confidence, 1f - speechLevel()))
    }

    /**
     * When the window around the expected beat has passed: the best onset in it becomes the beat
     * and nudges the grid. Without one, while a steady beat is known, the beat is filled in from
     * the grid (talking or a quiet moment covered it).
     */
    private fun predictBeats(n: Long) {
        if (period <= 0.0) return
        val decided = n - LOOKAHEAD
        val tolerance = period * GRID_TOLERANCE
        while (gridNext + tolerance < decided) {
            var next = gridNext + period
            if (pendingScore > 0f && n - pendingAt < HISTORY / 2) {
                val strength = gated(pendingStrength)
                if (strength >= 0.22f) {
                    emit(pendingAt, n, strength, real = true)
                    lastOnset = pendingAt
                }
                lastOnGrid = pendingAt
                // Follow the beats that really happened (a phase-locked grid).
                next += (pendingAt - gridNext) * 0.3
            } else {
                val at = gridNext.roundToInt().toLong()
                val recent = decided - lastOnGrid < (3f / hopSeconds)
                if (confidence > 0.35f && recent && n - at < HISTORY / 2) emit(at, n, 0.35f + 0.3f * confidence, real = false)
            }
            pendingScore = 0f
            gridNext = next
        }
    }

    /**
     * Writes a beat that happened at hop [at] into the envelope, up to the newest hop [n]. Only
     * [real] beats (heard, not filled in from the grid) count for [onset].
     */
    private fun emit(at: Long, n: Long, strength: Float, real: Boolean) {
        // The flux peaks when the onset is near the middle of the analysis window, one hop back.
        val from = maxOf(at - 1, n - HISTORY / 2, 0L)
        var v = strength
        var i = from
        while (i <= n) {
            val s = (i % HISTORY).toInt()
            if (hopBeat[s] < v) hopBeat[s] = v
            v *= kBeat
            i++
        }
        envelope = max(envelope, v / kBeat)
        if (real) {
            onsets[(onsetTotal % onsets.size).toInt()] = at * HOP
            onsetTotal++
        }
    }

    private val acfRaw = FloatArray(128)
    /** Autocorrelation averaged over the last estimates, per curve: chance periodicity in talking averages out. */
    private val acfSmooth = Array(2) { FloatArray(128) }
    private var acfLag = 0f
    private var acfPeak = 0f

    /**
     * Autocorrelation of [curve] over the last ACF_WINDOW hops up to [c], weighted by a tempo
     * preference around 120 BPM (Ellis 2007). Leaves the best lag in [acfLag] and its normalized
     * correlation (0..1, how periodic the curve is) in [acfPeak]; false when there is nothing.
     */
    private fun periodicity(curve: FloatArray, c: Long, k: Int): Boolean {
        val smooth = acfSmooth[k]
        var mean = 0f
        for (i in 0 until ACF_WINDOW) {
            val o = curve[idx(c - ACF_WINDOW + 1 + i)]
            acfBuffer[i] = o
            mean += o
        }
        mean /= ACF_WINDOW
        var zero = 0f
        for (i in 0 until ACF_WINDOW) {
            acfBuffer[i] -= mean
            zero += acfBuffer[i] * acfBuffer[i]
        }
        if (zero <= 1e-6f) {
            for (row in smooth.indices) smooth[row] *= 0.7f
            return false
        }
        zero /= ACF_WINDOW
        val preferred = 60.0 * rate / (HOP * 120.0)
        val minLag = (60.0 * rate / (HOP * 200.0)).toInt()
        val maxLag = (60.0 * rate / (HOP * 60.0)).toInt().coerceAtMost(acfRaw.size - 2)
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0f
            for (i in lag until ACF_WINDOW) s += acfBuffer[i] * acfBuffer[i - lag]
            acfRaw[lag] = s / (ACF_WINDOW - lag) / zero
            smooth[lag] += (acfRaw[lag] - smooth[lag]) * 0.3f
        }
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val octaves = ln(lag / preferred) / ln(2.0)
            val score = smooth[lag] * exp(-0.5 * (octaves / 0.9) * (octaves / 0.9))
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return false
        // Parabolic interpolation for a lag between whole hops.
        val a = smooth[bestLag - 1]
        val b = smooth[bestLag]
        val d = smooth[bestLag + 1]
        val denominator = a - 2 * b + d
        acfLag = bestLag + if (denominator < 0f) (0.5f * (a - d) / denominator).coerceIn(-0.5f, 0.5f) else 0f
        acfPeak = b
        return true
    }

    /** Tempo from the clearer of the full onset curve and the kick drum alone, and where the beats fall. */
    private fun updateTempo(c: Long) {
        var lag = 0f
        var raw = 0f
        var curve = odf
        if (periodicity(odf, c, 0)) {
            lag = acfLag
            raw = acfPeak
        }
        if (periodicity(kickOdf, c, 1) && acfPeak > raw) {
            lag = acfLag
            raw = acfPeak
            curve = kickOdf
        }
        if (lag <= 0f) {
            confidence *= 0.7f
            return
        }
        val bestRaw = raw
        val steady = period > 0.0 && abs(lag - period) / period < 0.05
        if (period == 0.0 || abs(lag - period) / period > 0.08) period = lag.toDouble() else period += (lag - period) * 0.3
        bpm = (60.0 * rate / (HOP * period)).toFloat()

        // Phase: the offset whose beats line up with the strongest onsets of the last six beats.
        var bestPhase = 0
        var bestSum = -1f
        val steps = period.toInt().coerceAtLeast(1)
        for (phase in 0 until steps) {
            var s = 0f
            for (k in 0 until 6) {
                val at = (c - phase - k * period).roundToInt().toLong()
                if (at < 0 || c - at >= HISTORY - 1) break
                s += curve[(at % HISTORY).toInt()]
            }
            if (s > bestSum) {
                bestSum = s
                bestPhase = phase
            }
        }
        val lastBeat = (c - bestPhase).toDouble()

        // Comb test: on real beats the onsets stand out against the time between them. Talking
        // can look periodic for a moment, but its onsets are spread out, not stacked on a grid.
        var onBeats = 0f
        var beats = 0
        for (k in 0 until 8) {
            val at = (lastBeat - k * period).roundToInt().toLong()
            if (at < 1 || c - at >= HISTORY - 2) break
            onBeats += max(curve[idx(at - 1)], max(curve[idx(at)], curve[idx(at + 1)]))
            beats++
        }
        var all = 0f
        val span = (beats * period).toInt().coerceAtLeast(1)
        for (i in 0 until span) all += curve[idx(c - i)]
        val contrast = if (beats > 0 && all > 0f) (onBeats / beats) / (all / span) else 0f
        // Averaged over time, talking alone stays below about 0.15; a steady beat under loud
        // talking reaches 0.25 to 0.55, plain music 0.9 and more.
        val periodic = ((bestRaw - 0.16f) / 0.18f).coerceIn(0f, 1f)
        val stacked = ((contrast - 2f) / 2.5f).coerceIn(0f, 1f)
        // Rises only while the tempo holds still; drops quickly when it doesn't.
        val sure = periodic * stacked * (if (steady) 1f else 0.3f)
        confidence += (sure - confidence) * (if (sure > confidence) 0.25f else 0.5f)
        // Re-anchor the grid when it has drifted off the beats (or when starting).
        val offset = ((gridNext - lastBeat) % period + period) % period
        val drift = minOf(offset, period - offset)
        if (gridNext == 0.0 || drift > period * 0.2) {
            gridNext = lastBeat
            while (gridNext <= c - period * GRID_TOLERANCE) gridNext += period
            pendingScore = 0f
        }
    }

    /** Ring slot of hop [hop]; hops before the start land on slots that are still empty. */
    private fun idx(hop: Long) = Math.floorMod(hop, HISTORY.toLong()).toInt()

    private fun speechLevel() = ((speech - 0.45f) / 0.3f).coerceIn(0f, 1f)

    /** In-place radix-2 FFT of [re]/[im], already in bit-reversed order. */
    private fun fft() {
        var size = 2
        while (size <= FRAME) {
            val half = size / 2
            val step = FRAME / size
            var start = 0
            while (start < FRAME) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    companion object {
        const val OUT_ENERGY = 0
        const val OUT_BEAT = 1
        const val OUT_BASS = 2
        const val OUT_TREBLE = 3
        const val OUT_PHASE = 4
        /** Size of the array [read] fills. */
        const val OUT_SIZE = 5

        const val HOP = 512 // about 11 ms
        const val FRAME = 1024
        const val HISTORY = 512 // about 6 s
        private const val LOOKAHEAD = 3 // hops waited before deciding on a peak (35 ms)
        private const val MEAN_BACK = 24 // hops of history for the adaptive threshold (0.3 s)
        private const val MIN_GAP = 9 // at least 0.1 s between beats
        private const val ACF_WINDOW = 384 // 4.5 s of onset curve for the tempo
        private const val TEMPO_EVERY = 43L
        private const val TEMPO_START = 172L // first tempo after 2 s
        private const val GRID_TOLERANCE = 0.12 // share of a beat an onset may be off the grid // re-estimate the tempo about twice a second
        private const val LOG_SCALE = 1000f
        private const val SUB_SCALE = 200f
        private const val SUB_WEIGHT = 3f
        /** Stage quality factors of a 6th-order Butterworth filter. */
        private val SUB_Q = doubleArrayOf(0.5176, 0.7071, 1.9319)

        private val EDGES = floatArrayOf(
            30f, 70f, 110f, 160f, 230f, 320f, 450f, 640f, 900f, 1250f, 1750f, 2450f, 3400f, 4800f, 6700f, 9400f, 13000f, 18000f,
        )

        /**
         * The sub-bass is handled by its own filter; in the spectrum the low bands where a voice's
         * fundamental lies and the voice range count least, snare and cymbals most.
         */
        private val WEIGHT = floatArrayOf(
            0.3f, 0.35f, 0.2f, 0.15f, 0.12f, 0.12f, 0.12f, 0.12f, 0.12f, 0.12f, 0.15f, 0.25f, 0.5f, 0.6f, 0.55f, 0.4f, 0.3f,
        )

        /** Bands where speech has most of its energy (230 Hz to 3.4 kHz). */
        private val VOICE = 4..11
    }
}

/** One second-order low-pass stage (RBJ cookbook), for the sub-bass filter. */
private class SubStage(rate: Int, frequency: Double, q: Double) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        val w = 2 * PI * frequency / rate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = ((1 - c) / 2 / a0).toFloat()
        b1 = ((1 - c) / a0).toFloat()
        b2 = b0
        a1 = (-2 * c / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}
