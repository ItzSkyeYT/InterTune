/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One listen of the phone's own sound, made for short videos (his design and his two corrections
 * of 16 Oct 2026): hear as much of the clip as there is, notice when the video starts over, and ask
 * Shazam about the whole clip rather than a few seconds of it. Four seconds of a remix's vocals
 * matched the original song; the clip's own beat is what tells the versions apart.
 *
 * Fed the recording in pieces of any length by [add], which says when to ask and about what:
 * - as soon as the clip is heard to repeat, about one whole turn of it (at most [MOST_ASKED_SECONDS]);
 * - after [MOST_ASKED_SECONDS] of sound with no repeat, about the last [MOST_ASKED_SECONDS];
 * - then again every [AGAIN_AFTER_SECONDS] of new sound, until [over].
 *
 * Silence before the first sound is not kept: from InterTune's screen the video is only started
 * after the listen is.
 */
class ClipListen(private val rate: Int = SIGNATURE_SAMPLE_RATE_HZ) {

    /** What to ask Shazam about, and why, for the log. */
    class Question(val samples: ShortArray, val why: String) {
        val seconds: Double get() = samples.size.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
    }

    private val kept = ShortArray(rate * MOST_HEARD_SECONDS)
    private var heard = 0
    private var waited = 0
    private var askedAt = -1
    private var askedTurn = false
    private val loops = LoopFinder(rate)

    /** How long the clip is once it has been heard to repeat, in seconds. */
    var loopSeconds: Double? = null
        private set

    /** The best repeat looked at, as similarity and seconds, for the log. */
    val closestRepeat: String get() = loops.best?.let { "%.2f at %.1f s".format(it.first, it.second) } ?: "none"

    /** Whether any sound came at all. */
    val heardAnything: Boolean get() = heard > 0

    /** Listened as long as it will: the answer that came last is the answer. */
    var over = false
        private set

    fun add(piece: ShortArray): Question? {
        if (over) return null
        waited += piece.size
        if (heard == 0 && silent(piece)) {
            if (waited >= rate * MOST_WAITED_SECONDS) over = true
            return null
        }
        val room = kept.size - heard
        val taken = min(room, piece.size)
        piece.copyInto(kept, heard, 0, taken)
        heard += taken
        loops.add(piece, taken)

        if (loopSeconds == null) loopSeconds = loops.find()
        val question = next()
        if (heard >= kept.size || waited >= rate * MOST_WAITED_SECONDS) over = true
        return question
    }

    private fun next(): Question? {
        val most = rate * MOST_ASKED_SECONDS
        val loop = loopSeconds
        if (loop != null) {
            val turn = (loop * rate).roundToInt()
            // A question already covered a whole turn: the clip has nothing more to say.
            if (askedTurn || (askedAt >= 0 && askedAt >= turn && turn <= most)) {
                over = true
                return null
            }
            askedTurn = true
            askedAt = heard
            // One whole turn when it fits, else the turn's first stretch, which no question
            // asked about yet: the questions before this one were about the latest sound.
            val start = heard - turn
            return Question(kept.copyOfRange(start, start + min(turn, most)), "the clip repeats every ${"%.1f".format(loop)} s")
        }
        if (askedAt < 0 && heard >= most) {
            askedAt = heard
            return Question(kept.copyOfRange(heard - most, heard), "no repeat in the first ${MOST_ASKED_SECONDS} s")
        }
        if (askedAt >= 0 && heard - askedAt >= rate * AGAIN_AFTER_SECONDS) {
            askedAt = heard
            return Question(kept.copyOfRange(heard - most, heard), "${AGAIN_AFTER_SECONDS} s more")
        }
        return null
    }

    private fun silent(piece: ShortArray): Boolean {
        var sum = 0.0
        for (s in piece) sum += s.toDouble() * s
        return piece.isEmpty() || sqrt(sum / piece.size) < SILENT_RMS
    }

    companion object {
        /** The longest stretch Shazam is asked about at once: what the microphone's windows hold. */
        const val MOST_ASKED_SECONDS = 12

        /** New sound wanted before asking again, when the last question named nothing. */
        const val AGAIN_AFTER_SECONDS = 6

        /** Sound kept at most, after which the listen ends. */
        const val MOST_HEARD_SECONDS = 30

        /** Time waited at most, silence first included: the video may be started after the listen. */
        const val MOST_WAITED_SECONDS = 40

        /** About -60 dB. A phone's own sound is digital: nothing playing is zeros. */
        private const val SILENT_RMS = 33.0
    }
}

/**
 * Finds the length of a clip that plays over and over, the way a short video does, from its sound:
 * the latest [MATCH_SECONDS] heard again, frame for frame, some seconds before.
 *
 * Each frame of 32 ms is reduced to its loudness in [BANDS] bands, the spectrum's general slope
 * taken out, so that two moments compare by what is playing rather than by being music at all.
 * A video restarting repeats its sound exactly; a chorus that comes back may too, which ends the
 * listen with a whole chorus heard, no harm done.
 */
internal class LoopFinder(private val rate: Int) {

    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / FRAME)).toFloat() }
    private val edges = bandEdges(rate)
    private val pending = FloatArray(FRAME)
    private var pendingCount = 0
    private val frames = ArrayList<FloatArray?>()

    /** The best match the last [find] saw, for the log: similarity and seconds. */
    var best: Pair<Double, Double>? = null
        private set

    fun add(piece: ShortArray, count: Int = piece.size) {
        var i = 0
        while (i < count) {
            val take = min(FRAME - pendingCount, count - i)
            for (k in 0 until take) pending[pendingCount + k] = piece[i + k].toFloat()
            pendingCount += take
            i += take
            if (pendingCount == FRAME) {
                frames += features(pending)
                pending.copyInto(pending, 0, HOP, FRAME)
                pendingCount = FRAME - HOP
            }
        }
    }

    /** The clip's length in seconds once its sound has come round again, or null. */
    fun find(): Double? {
        val match = (MATCH_SECONDS * rate / HOP).roundToInt()
        val shortest = (SHORTEST_SECONDS * rate / HOP).roundToInt()
        val n = frames.size
        if (n < shortest + match) return null

        // The slope every frame of music shares, taken out band by band.
        val mean = DoubleArray(BANDS)
        var counted = 0
        for (f in frames) if (f != null) {
            for (b in 0 until BANDS) mean[b] += f[b].toDouble()
            counted++
        }
        if (counted == 0) return null
        for (b in 0 until BANDS) mean[b] /= counted
        val shapes = frames.map { f ->
            f?.let {
                val v = DoubleArray(BANDS) { b -> it[b] - mean[b] }
                val norm = sqrt(v.sumOf { x -> x * x })
                if (norm < 1e-6) null else DoubleArray(BANDS) { b -> v[b] / norm }
            }
        }

        var bestLag = -1
        var bestScore = -1.0
        for (lag in shortest..(n - match)) {
            var sum = 0.0
            var pairs = 0
            for (t in n - match until n) {
                val a = shapes[t] ?: continue
                val b = shapes[t - lag] ?: continue
                var dot = 0.0
                for (k in 0 until BANDS) dot += a[k] * b[k]
                sum += dot
                pairs++
            }
            // Mostly silence is no proof of anything.
            if (pairs < match * 8 / 10) continue
            val score = sum / pairs
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        val seconds = bestLag.toDouble() * HOP / rate
        best = bestScore to seconds
        return if (bestScore >= SIMILAR) seconds else null
    }

    private fun features(samples: FloatArray): FloatArray? {
        val re = DoubleArray(FRAME) { (samples[it] * window[it]).toDouble() }
        val im = DoubleArray(FRAME)
        fft(re, im)
        val bands = FloatArray(BANDS)
        var total = 0.0
        for (b in 0 until BANDS) {
            var power = 0.0
            for (bin in edges[b] until edges[b + 1]) power += re[bin] * re[bin] + im[bin] * im[bin]
            total += power
            bands[b] = (10 * log10(power + 1.0)).toFloat()
        }
        return if (total < SILENT_POWER) null else bands
    }

    companion object {
        const val FRAME = 512
        const val HOP = 256
        const val BANDS = 24

        /** The shortest clip taken for a loop. Shorter repeats are a beat or a bar of music. */
        const val SHORTEST_SECONDS = 3.0

        /** Sound that must come round again, frame for frame, before it is called a loop. */
        const val MATCH_SECONDS = 3.0

        /** How alike the two stretches must be, 1 being identical. */
        const val SIMILAR = 0.9

        /** A frame quieter than about -60 dB carries nothing to compare. */
        private const val SILENT_POWER = FRAME.toDouble() * FRAME * 33.0 * 33.0 / 8

        private fun bandEdges(rate: Int): IntArray {
            val binHz = rate.toDouble() / FRAME
            val low = 125.0
            val high = min(7000.0, rate / 2.0 - binHz)
            val edges = IntArray(BANDS + 1)
            var last = 0
            for (i in 0..BANDS) {
                val hz = low * (high / low).pow(i.toDouble() / BANDS)
                val bin = max((hz / binHz).roundToInt(), last + 1)
                edges[i] = bin
                last = bin
            }
            return edges
        }

        /** In place, radix 2: [re] and [im] have a power of two's length. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle)
                val wi = sin(angle)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
