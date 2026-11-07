/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.BINS
import com.dd3boh.outertune.parts.Scnet.FRAMES
import com.dd3boh.outertune.parts.Scnet.HOP
import com.dd3boh.outertune.parts.Scnet.N_FFT
import com.dd3boh.outertune.parts.Scnet.PADDED
import com.dd3boh.outertune.parts.Scnet.SEGMENT

/**
 * SCNet's STFT and its inverse for one segment, as stemlib.split_scnet calls torch.stft and
 * torch.istft (stemlib.py:106-121): the segment with [Scnet.TAIL] zeros after it, padded by
 * reflection by half a frame at each end, 4096-point frames every 1024 samples, no window (a
 * rectangle), scaled by 1/64 each way (normalized=True). Back, every frame's inverse is added
 * where it came from and divided by how many frames cover each sample. The imaginary parts of the
 * lowest and highest bins are dropped on the way back, as torch's inverse real FFT drops them.
 *
 * Both channels go through one complex FFT: left as the real part, right as the imaginary one,
 * pulled apart after by the symmetry of a real signal's spectrum, and the same trick on the way
 * back puts a stem's two channels through one inverse. That halves the work: per segment, 476
 * forward and 1,904 inverse 4096-point FFTs.
 *
 * Holds about 13 MB of working arrays; one per thread.
 */
class ScnetStft {
    private val fft = Fft(N_FFT)

    /**
     * [BLOCK] frames at a time: a frame's bins are FRAMES apart in the model's layout, so one frame
     * at a time touched a cache line per bin and read the 15.6 MB spectrogram once per frame. In
     * blocks every line is read once: both transforms together took half the time on the PC.
     */
    private val blockRe = Array(BLOCK) { DoubleArray(N_FFT) }
    private val blockIm = Array(BLOCK) { DoubleArray(N_FFT) }

    /** A segment's two channels as the frames read them: zero tail and reflected ends included. */
    private val left = FloatArray(PADDED + N_FFT)
    private val right = FloatArray(PADDED + N_FFT)
    private val sumLeft = DoubleArray(PADDED + N_FFT)
    private val sumRight = DoubleArray(PADDED + N_FFT)

    /**
     * The model's input for one segment: [segLeft] and [segRight] hold [SEGMENT] samples each, and
     * [spec] receives [Scnet.SPEC_SIZE] floats, rows left real, left imaginary, right real, right
     * imaginary, each [BINS] by [FRAMES].
     */
    fun forward(segLeft: FloatArray, segRight: FloatArray, spec: FloatArray) {
        require(segLeft.size >= SEGMENT && segRight.size >= SEGMENT && spec.size >= Scnet.SPEC_SIZE)
        frameSource(segLeft, left)
        frameSource(segRight, right)
        val row = BINS * FRAMES
        var t0 = 0
        while (t0 < FRAMES) {
            val count = minOf(BLOCK, FRAMES - t0)
            for (j in 0 until count) {
                val re = blockRe[j]
                val im = blockIm[j]
                val at = (t0 + j) * HOP
                for (k in 0 until N_FFT) {
                    re[k] = left[at + k].toDouble()
                    im[k] = right[at + k].toDouble()
                }
                fft.forward(re, im)
            }
            // Z = L + iR, so L(f) = (Z(f) + conj Z(n-f)) / 2 and R(f) = (Z(f) - conj Z(n-f)) / 2i.
            for (f in 0 until BINS) {
                val g = (N_FFT - f) and (N_FFT - 1)
                val cell = f * FRAMES + t0
                for (j in 0 until count) {
                    val re = blockRe[j]
                    val im = blockIm[j]
                    spec[cell + j] = ((re[f] + re[g]) * HALF_SCALE).toFloat()
                    spec[row + cell + j] = ((im[f] - im[g]) * HALF_SCALE).toFloat()
                    spec[2 * row + cell + j] = ((im[f] + im[g]) * HALF_SCALE).toFloat()
                    spec[3 * row + cell + j] = ((re[g] - re[f]) * HALF_SCALE).toFloat()
                }
            }
            t0 += count
        }
    }

    /**
     * One stem back to sound: [spec] holds its spectrogram from [offset] in the layout [forward]
     * writes (each stem of the model's output is one), and [outLeft] and [outRight] receive the
     * segment's [SEGMENT] samples from [outOffset].
     */
    fun inverse(spec: FloatArray, offset: Int, outLeft: FloatArray, outRight: FloatArray, outOffset: Int = 0) {
        require(spec.size >= offset + Scnet.SPEC_SIZE)
        require(outLeft.size >= outOffset + SEGMENT && outRight.size >= outOffset + SEGMENT)
        sumLeft.fill(0.0)
        sumRight.fill(0.0)
        val row = BINS * FRAMES
        val nyquist = N_FFT / 2
        var t0 = 0
        while (t0 < FRAMES) {
            val count = minOf(BLOCK, FRAMES - t0)
            // Z = A + iB, with A the left channel's spectrum and B the right's, both completed as
            // a real signal's: the inverse then gives left in the real part, right in the imaginary.
            for (f in 0 until BINS) {
                val cell = offset + f * FRAMES + t0
                val edge = f == 0 || f == nyquist
                for (j in 0 until count) {
                    val ar = spec[cell + j].toDouble()
                    val ai = if (edge) 0.0 else spec[row + cell + j].toDouble()
                    val br = spec[2 * row + cell + j].toDouble()
                    val bi = if (edge) 0.0 else spec[3 * row + cell + j].toDouble()
                    val re = blockRe[j]
                    val im = blockIm[j]
                    re[f] = ar - bi
                    im[f] = ai + br
                    if (!edge) {
                        re[N_FFT - f] = ar + bi
                        im[N_FFT - f] = br - ai
                    }
                }
            }
            for (j in 0 until count) {
                val re = blockRe[j]
                val im = blockIm[j]
                fft.inverse(re, im)
                val at = (t0 + j) * HOP
                for (k in 0 until N_FFT) {
                    sumLeft[at + k] += re[k]
                    sumRight[at + k] += im[k]
                }
            }
            t0 += count
        }
        for (p in 0 until SEGMENT) {
            val q = p + N_FFT / 2
            val w = OUT_SCALE[p]
            outLeft[outOffset + p] = (sumLeft[q] * w).toFloat()
            outRight[outOffset + p] = (sumRight[q] * w).toFloat()
        }
    }

    /** [dst] gets the segment with its zero tail, reflected by half a frame at both ends. */
    private fun frameSource(seg: FloatArray, dst: FloatArray) {
        val half = N_FFT / 2
        for (q in dst.indices) {
            var j = q - half
            if (j < 0) j = -j else if (j >= PADDED) j = 2 * (PADDED - 1) - j
            dst[q] = if (j < SEGMENT) seg[j] else 0f
        }
    }

    companion object {
        /** Frames transformed together; 16 frames of doubles are 1 MB. */
        private const val BLOCK = 16

        /** A half from pulling the two channels apart, times the 1/64 of normalized=True. */
        private const val HALF_SCALE = 0.5 / 64

        /**
         * For each sample of the segment: 1/64 (normalized=True) over the number of frames that
         * cover it, 4 inside and 3 at the ends.
         */
        private val OUT_SCALE = DoubleArray(SEGMENT) { p -> 1.0 / 64 / covering(p + N_FFT / 2) }

        /** How many frames cover sample [q] of the padded segment: frames t with t * HOP <= q < t * HOP + N_FFT. */
        internal fun covering(q: Int): Int {
            val past = q - N_FFT + 1
            val first = if (past <= 0) 0 else (past + HOP - 1) / HOP
            val last = minOf(FRAMES - 1, q / HOP)
            return last - first + 1
        }
    }
}
