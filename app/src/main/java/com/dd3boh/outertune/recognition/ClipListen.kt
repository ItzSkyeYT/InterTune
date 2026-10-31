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
 * What one listen of the phone's own sound hears, made for short videos (his design and his two
 * corrections of 16 Oct 2026: hear as much of the clip as there is, notice when the video starts
 * over, and ask Shazam about the clip rather than a few seconds of it) and changed on 31 Oct 2026,
 * after his reel of an organ playing a dance tune was named "Sunshine" by Dance Nation, the first
 * guess of the first twelve seconds: it does not listen long enough to tell a guess from the song.
 *
 * Fed the recording in pieces of any length by [add], which says when there is a window to ask
 * Shazam about, and keeps the sound until the listen is [over]:
 * - the first window at [MOST_ASKED_SECONDS] of sound, or as soon as the clip is heard to repeat
 *   once there are [LOOP_FIRST_SECONDS] of it;
 * - then another every [AGAIN_AFTER_SECONDS] of new sound, each about the last
 *   [MOST_ASKED_SECONDS], so that windows overlap by half and a second one is more than the first
 *   again;
 * - until [MOST_HEARD_SECONDS] of sound, [QUIET_END_SECONDS] of silence after it, or the
 *   [ClipSession] has its answer.
 *
 * A repeat does not end the listen, and does not shorten a window. Measured on 21 Oct 2026 on 44
 * songs of the kind reels play, a stretch of music repeated for a few seconds in one song of five
 * (two or four bars), which ended the listen after one window with the first guess. A repeat that
 * holds for a whole turn after it was first seen is a loop ([loopHeld]): then the whole clip has
 * been heard, and what it holds is in the windows asked so far.
 *
 * Silence before the first sound is not kept: from InterTune's screen the video is only started
 * after the listen is.
 */
class ClipListen(private val rate: Int = SIGNATURE_SAMPLE_RATE_HZ) {

    /**
     * What to ask Shazam about, and why, for the log. [fromSample] is where in the sound heard
     * (silence before the first sound left out) the window starts, for [ClipEvidence].
     */
    class Question(val samples: ShortArray, val why: String, val fromSample: Int = 0) {
        val seconds: Double get() = samples.size.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
        val fromSeconds: Double get() = fromSample.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
    }

    private val kept = ShortArray(rate * MOST_HEARD_SECONDS)
    private var heard = 0
    private var waited = 0
    private var quiet = 0
    private var askedAt = -1
    private val loops = LoopFinder(rate)

    /** How long the clip is once it has been heard to repeat, in seconds, until the repeat is lost. */
    var loopSeconds: Double? = null
        private set

    /** The repeat has held for a whole turn since it was first seen: the clip is a loop, all of it heard. */
    var loopHeld = false
        private set

    /** The best repeat looked at, as similarity and seconds, for the log. */
    val closestRepeat: String get() = loops.best?.let { "%.2f at %.1f s".format(it.first, it.second) } ?: "none"

    /** Whether any sound came at all. */
    val heardAnything: Boolean get() = heard > 0

    /** Seconds of sound heard, the silence before the first sound left out. */
    val heardSeconds: Double get() = heard.toDouble() / rate

    /** Listened as long as it will: the sound ran out, or the video did. What came last is the answer. */
    var over = false
        private set

    fun add(piece: ShortArray): Question? {
        if (over) return null
        waited += piece.size
        val silent = silent(piece)
        if (heard == 0 && silent) {
            if (waited >= rate * MOST_WAITED_SECONDS) over = true
            return null
        }
        quiet = if (silent) quiet + piece.size else 0
        val room = kept.size - heard
        val taken = min(room, piece.size)
        piece.copyInto(kept, heard, 0, taken)
        heard += taken
        loops.add(piece, taken)

        loopSeconds = loops.find()
        loopHeld = loopSeconds != null && loops.held
        val question = next()
        val ends = heard >= kept.size || waited >= rate * MOST_WAITED_SECONDS || quiet >= rate * QUIET_END_SECONDS
        if (ends) over = true
        return question ?: if (ends) last() else null
    }

    private fun next(): Question? {
        val loop = loopSeconds
        val first = askedAt < 0
        val due = if (first) heard >= rate * MOST_ASKED_SECONDS || (loop != null && heard >= rate * LOOP_FIRST_SECONDS)
        else heard - askedAt >= rate * AGAIN_AFTER_SECONDS
        if (!due) return null
        return question(
            when {
                first && loop != null -> "the clip repeats every ${"%.1f".format(loop)} s"
                first -> "the first $MOST_ASKED_SECONDS s"
                else -> "$AGAIN_AFTER_SECONDS s more"
            }
        )
    }

    /**
     * When the sound ends, what came after the last window, if it is enough to say anything: a video
     * of five seconds that does not loop was never asked about, with the windows every six seconds
     * from twelve.
     */
    private fun last(): Question? = question("the sound stopped after ${"%.0f".format((heard - quiet).toDouble() / rate)} s")

    /**
     * A window about the last [MOST_ASKED_SECONDS] of sound, or null when there is not [LEAST_ASKED_SECONDS]
     * of sound since the last one. A pause or the end of the video is no part of a window: it ends where
     * the sound did.
     */
    private fun question(why: String): Question? {
        val end = heard - quiet
        if (end - max(askedAt, 0) < rate * LEAST_ASKED_SECONDS) return null
        askedAt = heard
        val take = min(rate * MOST_ASKED_SECONDS, end)
        return Question(kept.copyOfRange(end - take, end), why, end - take)
    }

    private fun silent(piece: ShortArray): Boolean {
        var sum = 0.0
        for (s in piece) sum += s.toDouble() * s
        return piece.isEmpty() || sqrt(sum / piece.size) < SILENT_RMS
    }

    companion object {
        /** The longest stretch Shazam is asked about at once: what the microphone's windows hold. */
        const val MOST_ASKED_SECONDS = 12

        /** New sound wanted before asking again. Half a window: a second window that is more than the first again. */
        const val AGAIN_AFTER_SECONDS = 6

        /** The least sound Shazam is asked about: it matches comfortably from about five seconds. */
        const val LEAST_ASKED_SECONDS = 5

        /** The first window is asked for at this much sound when the clip has been heard to repeat. */
        const val LOOP_FIRST_SECONDS = 6

        /**
         * Sound kept at most, after which the listen ends: a minute, nine windows. A reel runs up to
         * three minutes, and its song is usually named long before; what is not named by now is not
         * going to be by listening on (an organ's version of a dance tune, 31 Oct 2026).
         */
        const val MOST_HEARD_SECONDS = 60

        /** Time waited at most, silence first included: the video may be started after the listen. */
        const val MOST_WAITED_SECONDS = 70

        /** Silence after the sound that means the video ended or was paused. */
        const val QUIET_END_SECONDS = 6

        /** About -60 dB. A phone's own sound is digital: nothing playing is zeros. */
        private const val SILENT_RMS = 33.0
    }
}

/**
 * Finds the length of a clip that plays over and over, the way a short video does, from its sound:
 * the latest [MATCH_SECONDS] heard again, frame for frame, some seconds before.
 *
 * Each frame of 64 ms, every 16 ms, is reduced to its loudness in [BANDS] bands, the spectrum's
 * general slope taken out, so that two moments compare by what is playing rather than by being
 * music at all. A video's loop is rarely a whole number of steps long; frames four steps wide still
 * line up when the repeat falls half a step between two (see [FRAME]).
 * A video restarting repeats its sound exactly; a chorus that comes back may too, and a bar or two
 * of a song does, often (one 30 s stretch in five): so the first sight of a repeat proves little,
 * and [held] says whether it kept on repeating for a whole turn after.
 */
internal class LoopFinder(private val rate: Int) {

    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / FRAME)).toFloat() }
    private val edges = bandEdges(rate)
    private val pending = FloatArray(FRAME)
    private var pendingCount = 0
    private val frames = ArrayList<FloatArray?>()

    /** The repeat in frames, once seen, and how many frames there were when it was. */
    private var lag = -1
    private var seenAt = 0

    /** The best match the last [find] saw, for the log: similarity and seconds. */
    var best: Pair<Double, Double>? = null
        private set

    /** The repeat seen has kept scoring for a whole turn since it was first seen. */
    var held = false
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

    /**
     * The clip's length in seconds once its sound has come round again, or null. Once a repeat was
     * seen it is only looked after, at the same length within [HOLD_SLACK] steps: while it still
     * scores it is reported, and [held] says it has for a whole turn; when it stops, the search
     * starts over.
     */
    fun find(): Double? {
        val match = (MATCH_SECONDS * rate / HOP).roundToInt()
        val shortest = (SHORTEST_SECONDS * rate / HOP).roundToInt()
        val n = frames.size
        if (n < shortest + match) return null
        val shapes = shapes()

        if (lag > 0) {
            var bestLag = -1
            var bestScore = -1.0
            for (l in (lag - HOLD_SLACK)..(lag + HOLD_SLACK)) {
                if (l < shortest || l > n - match) continue
                val s = scoreAt(shapes, l, n, match) ?: continue
                if (s > bestScore) {
                    bestScore = s
                    bestLag = l
                }
            }
            if (bestLag > 0 && bestScore >= SIMILAR) {
                lag = bestLag
                held = n - seenAt >= lag
                best = bestScore to lag.toDouble() * HOP / rate
                return lag.toDouble() * HOP / rate
            }
            // It stopped repeating: two bars of a song, or the clip changed. Look again.
            lag = -1
            held = false
        }

        var bestLag = -1
        var bestScore = -1.0
        for (l in shortest..(n - match)) {
            val score = scoreAt(shapes, l, n, match) ?: continue
            if (score > bestScore) {
                bestScore = score
                bestLag = l
            }
        }
        if (bestLag < 0) return null
        val seconds = bestLag.toDouble() * HOP / rate
        best = bestScore to seconds
        if (bestScore < SIMILAR) return null
        lag = bestLag
        seenAt = n
        held = false
        return seconds
    }

    /** Every frame as a unit vector of its bands with the slope all frames share taken out, null for silence. */
    private fun shapes(): List<DoubleArray?> {
        val mean = DoubleArray(BANDS)
        var counted = 0
        for (f in frames) if (f != null) {
            for (b in 0 until BANDS) mean[b] += f[b].toDouble()
            counted++
        }
        if (counted == 0) return frames.map { null }
        for (b in 0 until BANDS) mean[b] /= counted
        return frames.map { f ->
            f?.let {
                val v = DoubleArray(BANDS) { b -> it[b] - mean[b] }
                val norm = sqrt(v.sumOf { x -> x * x })
                if (norm < 1e-6) null else DoubleArray(BANDS) { b -> v[b] / norm }
            }
        }
    }

    /** How alike the latest [match] frames are to those [l] frames before them, or null when too much of it is silence. */
    private fun scoreAt(shapes: List<DoubleArray?>, l: Int, n: Int, match: Int): Double? {
        var sum = 0.0
        var pairs = 0
        for (t in n - match until n) {
            val a = shapes[t] ?: continue
            val b = shapes[t - l] ?: continue
            var dot = 0.0
            for (k in 0 until BANDS) dot += a[k] * b[k]
            sum += dot
            pairs++
        }
        // Mostly silence is no proof of anything.
        if (pairs < match * 8 / 10) return null
        return sum / pairs
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
        /**
         * Four steps wide. Measured on two real songs (experiments/features/phone-audio, 17 Oct
         * 2026): with 512, a loop half a step off its frames was only found at twice its length;
         * with 1024 the worst such loop scores 0.935 and no 30 s of a song that does not loop
         * passes 0.878, either side of [SIMILAR]. 2048, or steps of 128, let songs that do not
         * loop pass it.
         */
        const val FRAME = 1024
        const val HOP = 256
        const val BANDS = 24

        /** The shortest clip taken for a loop. Shorter repeats are a beat or a bar of music. */
        const val SHORTEST_SECONDS = 3.0

        /** Sound that must come round again, frame for frame, before it is called a loop. */
        const val MATCH_SECONDS = 3.0

        /** How alike the two stretches must be, 1 being identical. */
        const val SIMILAR = 0.9

        /** A repeat found is followed this many steps either side of its length. */
        const val HOLD_SLACK = 2

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
