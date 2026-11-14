package com.cannytvradio.player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic radio: a drum beat, a presenter talking over it, and talking alone. The voice is a
 * worst case on purpose: a low male voice whose fundamental reaches into the bass range, syllables
 * at about four per second, and hard plosives ("p", "t") between the beats.
 */
class BeatTrackerTest {
    private val rate = 44100

    private fun drums(seconds: Double, bpm: Double, out: FloatArray, level: Float = 0.5f): List<Double> {
        val beats = ArrayList<Double>()
        val step = 60.0 / bpm
        var t = 0.5
        var k = 0
        val random = Random(1)
        while (t < seconds - 0.4) {
            beats.add(t)
            val start = (t * rate).toInt()
            var phase = 0.0
            for (i in 0 until (0.3 * rate).toInt()) {
                val time = i.toDouble() / rate
                phase += 2 * PI * (50 + 100 * exp(-time / 0.03)) / rate
                var v = sin(phase) * exp(-time / 0.15)
                if (k % 2 == 1) v += (random.nextDouble() * 2 - 1) * 0.5 * exp(-time / 0.05) // snare on the off-count
                if (start + i < out.size) out[start + i] += (v * level).toFloat()
            }
            // Hi-hat on the half beat, as in most pop music.
            val half = ((t + step / 2) * rate).toInt()
            for (i in 0 until (0.04 * rate).toInt()) {
                if (half + i < out.size) out[half + i] += ((random.nextDouble() * 2 - 1) * 0.12 * exp(-i / (0.01 * rate))).toFloat()
            }
            k++
            t = 0.5 + k * step
        }
        return beats
    }

    private fun speech(from: Double, to: Double, out: FloatArray, level: Float) {
        val random = Random(7)
        var t = from
        var pitchPhase = 0.0
        while (t < to) {
            val length = 0.12 + random.nextDouble() * 0.12
            val f0 = 100 + random.nextDouble() * 50
            val start = (t * rate).toInt()
            val n = (length * rate).toInt()
            for (i in 0 until n) {
                val x = i.toDouble() / n
                val env = if (x < 0.15) x / 0.15 else if (x > 0.7) (1 - x) / 0.3 else 1.0
                val f = f0 * (1 + 0.1 * sin(2 * PI * x)) // pitch glide within the syllable
                pitchPhase += 2 * PI * f / rate
                var v = 0.0
                for (h in 1..18) v += sin(h * pitchPhase) / h * (if (h in 3..9) 1.6 else 1.0)
                if (start + i < out.size) out[start + i] += (v * env * 0.35 * level).toFloat()
            }
            // A plosive now and then: a short broadband pop, including low frequencies.
            if (random.nextDouble() < 0.35) {
                for (i in 0 until (0.012 * rate).toInt()) {
                    val v = (random.nextDouble() * 2 - 1) * exp(-i / (0.003 * rate)) + sin(2 * PI * 90 * i / rate) * exp(-i / (0.008 * rate))
                    if (start + i < out.size) out[start + i] += (v * 0.6 * level).toFloat()
                }
            }
            t += length + 0.03 + random.nextDouble() * 0.12
            if (random.nextDouble() < 0.1) t += 0.3 // a short pause between phrases
        }
    }

    private class Result(val beatCurve: FloatArray, val tracker: BeatTracker)

    private fun run(audio: FloatArray): Result {
        val tracker = BeatTracker(rate)
        for (s in audio) tracker.push(s)
        // Read the finished curve the way the screen does, hop by hop (the last 6 s are kept).
        val hops = audio.size / BeatTracker.HOP
        val curve = FloatArray(hops)
        val out = FloatArray(BeatTracker.OUT_SIZE)
        for (h in 0 until hops) {
            tracker.read((h + 1L) * BeatTracker.HOP, out)
            curve[h] = out[BeatTracker.OUT_BEAT]
        }
        return Result(curve, tracker)
    }

    private fun runLive(audio: FloatArray): Result {
        // The ring keeps 6 s, so read each hop shortly after it was analysed, like the screen does.
        val tracker = BeatTracker(rate)
        val hops = audio.size / BeatTracker.HOP
        val curve = FloatArray(hops)
        val out = FloatArray(BeatTracker.OUT_SIZE)
        val delay = 40 // hops, about the half second between processing and hearing
        for ((i, s) in audio.withIndex()) {
            tracker.push(s)
            if ((i + 1) % BeatTracker.HOP == 0) {
                val h = (i + 1) / BeatTracker.HOP - 1 - delay
                if (h >= 0) {
                    tracker.read((h + 1L) * BeatTracker.HOP, out)
                    curve[h] = out[BeatTracker.OUT_BEAT]
                }
            }
        }
        return Result(curve, tracker)
    }

    private val hop = BeatTracker.HOP.toDouble() / rate

    /** Starts of flashes: the beat curve jumps up. */
    private fun flashes(r: Result, from: Double): List<Double> =
        (1 until r.beatCurve.size).filter { it * hop >= from && r.beatCurve[it] - r.beatCurve[it - 1] > 0.2f }.map { it * hop }

    private fun check(r: Result, beats: List<Double>, from: Double, to: Double): Triple<Int, Int, Int> {
        val f = flashes(r, from).filter { it < to }
        val inRange = beats.filter { it >= from + 0.05 && it < to - 0.05 }
        val hit = inRange.count { b -> f.any { abs(it - b) < 0.07 } }
        val wrong = f.count { t -> beats.none { abs(it - t) < 0.09 } }
        return Triple(hit, inRange.size, wrong)
    }

    @Test
    fun findsTheBeatInMusic() {
        val audio = FloatArray(rate * 20)
        val beats = drums(20.0, 120.0, audio)
        val r = runLive(audio)
        val (hit, total, wrong) = check(r, beats, 3.0, 19.0)
        println("music: $hit of $total beats, $wrong false, tempo ${r.tracker.bpm}, sure ${r.tracker.confidence}")
        assertTrue(hit >= total * 0.95)
        assertTrue(wrong <= 2)
        assertTrue(abs(r.tracker.bpm - 120f) < 3f)
    }

    @Test
    fun keepsTheBeatWhileSomeoneTalksOverIt() {
        for (bpm in listOf(120.0, 95.0)) {
            val audio = FloatArray(rate * 30)
            val beats = drums(30.0, bpm, audio, level = 0.35f)
            // The presenter starts after 8 s, louder than the music.
            speech(8.0, 30.0, audio, level = 1.2f)
            val r = runLive(audio)
                val (hit, total, wrong) = check(r, beats, 10.0, 29.0)
            println("talking over $bpm BPM: $hit of $total beats, $wrong false, tempo ${r.tracker.bpm}, sure ${r.tracker.confidence}")
            assertTrue(hit >= total * 0.85)
            assertTrue(wrong <= total * 0.1)
        }
    }

    @Test
    fun staysCalmWhenThereIsOnlyTalking() {
        val audio = FloatArray(rate * 20)
        speech(0.0, 20.0, audio, level = 1.2f)
        val r = runLive(audio)
        val f = flashes(r, 3.0).filter { it < 19.0 }
        println("only talking: ${f.size} flashes in 16 s, tempo ${r.tracker.bpm}, sure ${r.tracker.confidence}")
        // Music at 120 BPM would flash 32 times here.
        assertTrue(f.size <= 6)
    }

    @Test
    fun readsTheFinishedCurve() {
        val audio = FloatArray(rate * 5)
        drums(5.0, 120.0, audio)
        val r = run(audio)
        assertTrue(r.beatCurve.any { it > 0.4f })
    }

    @Test
    fun followsBassTrebleAndTheBeatPhase() {
        val audio = FloatArray(rate * 12)
        drums(12.0, 120.0, audio)
        val tracker = BeatTracker(rate)
        for (sample in audio) tracker.push(sample)
        val out = FloatArray(BeatTracker.OUT_SIZE)
        var bassOnBeat = 0f
        var bassBetween = 0f
        var phases = 0
        // Positions up to 3 s back count as current: beats every 0.5 s from 0.5 s on.
        for (k in 0 until 5) {
            val beat = 9.0 + k * 0.5
            tracker.read(((beat + 0.03) * rate).toLong(), out)
            bassOnBeat += out[BeatTracker.OUT_BASS]
            tracker.read(((beat + 0.35) * rate).toLong(), out)
            bassBetween += out[BeatTracker.OUT_BASS]
            if (out[BeatTracker.OUT_PHASE] in 0.55f..0.85f) phases++
        }
        println("bass on beat ${bassOnBeat / 5}, between ${bassBetween / 5}, phase right $phases of 5")
        assertTrue(bassOnBeat > bassBetween * 1.5f)
        assertTrue(phases >= 4)
    }
}
