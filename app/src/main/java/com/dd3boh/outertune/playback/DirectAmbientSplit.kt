/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stereo recording taken apart into the sound itself and the air round it.
 *
 * What a mix puts somewhere between the speakers is the same signal in both channels, louder in
 * one than the other: a voice, a snare, a guitar panned half left. What the two channels do not
 * share is the hall it was played in, the reverb put on it, the wide effects. The first is
 * "direct" and the second "ambient", and the two always add up to the recording exactly, so
 * that whatever is done with the ambient part, nothing of the recording is lost or made up.
 *
 * Done note by note and not on the whole signal, because at any moment a voice is direct at its
 * own frequencies while the hall is ambient at all the others: short overlapping frames, each
 * turned into its frequencies, and for each frequency the two channels compared over the last
 * tenth of a second. The model is the usual one (a sound panned by level, plus an ambience as
 * strong in one channel as in the other and unrelated between them); its answer for how much of
 * a channel is ambience is the smaller of the two powers that the pair of channels breaks into.
 * A sound that is in one channel only is direct by that model, not ambient, which is what keeps a
 * guitar panned hard left where it was put.
 *
 * One sample in, one out, [DELAY] samples late: a frame cannot be judged before it is whole.
 */
internal class DirectAmbientSplit {

    var directLeft = 0f
        private set
    var directRight = 0f
        private set
    var ambientLeft = 0f
        private set
    var ambientRight = 0f
        private set

    private val inLeft = FloatArray(SIZE)
    private val inRight = FloatArray(SIZE)
    private val outLeft = FloatArray(2 * SIZE)
    private val outRight = FloatArray(2 * SIZE)
    private var at = 0
    private var sinceFrame = 0

    private val leftRe = FloatArray(SIZE)
    private val leftIm = FloatArray(SIZE)
    private val rightRe = FloatArray(SIZE)
    private val rightIm = FloatArray(SIZE)

    // Per frequency, over the last tenth of a second: each channel's power, and what they share.
    private val powerLeft = FloatArray(SIZE / 2 + 1)
    private val powerRight = FloatArray(SIZE / 2 + 1)
    private val sharedRe = FloatArray(SIZE / 2 + 1)
    private val sharedIm = FloatArray(SIZE / 2 + 1)

    /** Takes one frame of the recording and sets the four parts for the frame [DELAY] samples back. */
    fun process(left: Float, right: Float) {
        val i = at and (SIZE - 1)
        // What was here is the recording a whole frame ago, which is the frame now coming out.
        val wasLeft = inLeft[i]
        val wasRight = inRight[i]
        inLeft[i] = left
        inRight[i] = right
        if (++sinceFrame == HOP) {
            sinceFrame = 0
            frame()
        }
        val o = (at - SIZE) and (2 * SIZE - 1)
        ambientLeft = outLeft[o]
        ambientRight = outRight[o]
        outLeft[o] = 0f
        outRight[o] = 0f
        directLeft = wasLeft - ambientLeft
        directRight = wasRight - ambientRight
        at++
    }

    /** A seek: nothing of the old place is left to come out at the new one. */
    fun reset() {
        Arrays.fill(inLeft, 0f); Arrays.fill(inRight, 0f)
        Arrays.fill(outLeft, 0f); Arrays.fill(outRight, 0f)
        Arrays.fill(powerLeft, 0f); Arrays.fill(powerRight, 0f)
        Arrays.fill(sharedRe, 0f); Arrays.fill(sharedIm, 0f)
        at = 0
        sinceFrame = 0
        directLeft = 0f; directRight = 0f; ambientLeft = 0f; ambientRight = 0f
    }

    /** The frame that ends on the sample just taken: its ambient part, added to what is on its way out. */
    private fun frame() {
        var j = (at + 1) and (SIZE - 1)
        for (n in 0 until SIZE) {
            leftRe[n] = inLeft[j] * WINDOW[n]
            leftIm[n] = 0f
            rightRe[n] = inRight[j] * WINDOW[n]
            rightIm[n] = 0f
            j = (j + 1) and (SIZE - 1)
        }
        fft(leftRe, leftIm, false)
        fft(rightRe, rightIm, false)

        for (k in 0..SIZE / 2) {
            val lr = leftRe[k]
            val li = leftIm[k]
            val rr = rightRe[k]
            val ri = rightIm[k]
            val pl = HOLD * powerLeft[k] + (1f - HOLD) * (lr * lr + li * li)
            val pr = HOLD * powerRight[k] + (1f - HOLD) * (rr * rr + ri * ri)
            val sr = HOLD * sharedRe[k] + (1f - HOLD) * (lr * rr + li * ri)
            val si = HOLD * sharedIm[k] + (1f - HOLD) * (li * rr - lr * ri)
            powerLeft[k] = pl
            powerRight[k] = pr
            sharedRe[k] = sr
            sharedIm[k] = si

            // Two unrelated signals never measure as sharing nothing over a tenth of a second:
            // they share about a quarter by chance. That much is taken off before it is believed.
            val shared = max(0f, sqrt(sr * sr + si * si) - CHANCE * sqrt(pl * pr)) / (1f - CHANCE)
            val apart = pl - pr
            val ambience = 0.5f * (pl + pr - sqrt(apart * apart + 4f * shared * shared))
            val ofLeft = if (pl > SILENT) (ambience / pl).coerceIn(0f, 1f) else 0f
            val ofRight = if (pr > SILENT) (ambience / pr).coerceIn(0f, 1f) else 0f

            leftRe[k] *= ofLeft; leftIm[k] *= ofLeft
            rightRe[k] *= ofRight; rightIm[k] *= ofRight
            if (k in 1 until SIZE / 2) {
                val m = SIZE - k
                leftRe[m] *= ofLeft; leftIm[m] *= ofLeft
                rightRe[m] *= ofRight; rightIm[m] *= ofRight
            }
        }

        fft(leftRe, leftIm, true)
        fft(rightRe, rightIm, true)
        var o = (at + 1 - SIZE) and (2 * SIZE - 1)
        for (n in 0 until SIZE) {
            val w = WINDOW[n] * OVERLAP
            outLeft[o] += leftRe[n] * w
            outRight[o] += rightRe[n] * w
            o = (o + 1) and (2 * SIZE - 1)
        }
    }

    companion object {
        /** A frame: 21 thousandths of a second at 48 kHz, which is also how late everything comes out. */
        const val SIZE = 1024
        const val DELAY = SIZE

        /** A new frame every quarter of one, so that every sample is in four. */
        private const val HOP = SIZE / 4

        /** How much of the last estimate is kept each frame: a tenth of a second's memory. */
        private const val HOLD = 0.948f

        /** What two unrelated signals appear to share over that long, by chance. */
        private const val CHANCE = 0.25f
        private const val SILENT = 1e-12f

        /** A Hann window going in and again coming out; four of those squared, a quarter apart, add up to 1.5. */
        private const val OVERLAP = 1f / 1.5f
        private val WINDOW = FloatArray(SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / SIZE)).toFloat() }

        private val COS = FloatArray(SIZE / 2) { cos(2.0 * PI * it / SIZE).toFloat() }
        private val SIN = FloatArray(SIZE / 2) { sin(2.0 * PI * it / SIZE).toFloat() }
        private val REVERSED = IntArray(SIZE) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(SIZE)) }

        /** [SIZE] complex numbers into their frequencies, or back. In place; back is divided by [SIZE]. */
        fun fft(re: FloatArray, im: FloatArray, back: Boolean) {
            for (i in 0 until SIZE) {
                val j = REVERSED[i]
                if (j > i) {
                    val r = re[i]; re[i] = re[j]; re[j] = r
                    val m = im[i]; im[i] = im[j]; im[j] = m
                }
            }
            var length = 2
            while (length <= SIZE) {
                val half = length / 2
                val step = SIZE / length
                var start = 0
                while (start < SIZE) {
                    var t = 0
                    for (k in 0 until half) {
                        val c = COS[t]
                        val s = if (back) SIN[t] else -SIN[t]
                        val a = start + k
                        val b = a + half
                        val xr = re[b] * c - im[b] * s
                        val xi = re[b] * s + im[b] * c
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                        t += step
                    }
                    start += length
                }
                length = length shl 1
            }
            if (back) {
                val scale = 1f / SIZE
                for (i in 0 until SIZE) { re[i] *= scale; im[i] *= scale }
            }
        }
    }
}
