/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.SEGMENT
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A window of a song split into its parts on the phone, the way the PC splits it
 * (experiments/features/automix/parts/tools/split_windows.py): the window, 16-bit and interleaved
 * at its own rate in one or two channels, brought to 44.1 kHz ([Resampler], torchaudio's filter),
 * through SCNet segment by segment at [overlap] ([ScnetPlan], added back with [ScnetSum]'s weights
 * to the bit), vocals, drums and bass brought back to the window's rate, melody the window minus
 * those three, all four in float32 as the PC takes them and rounded to 16 bits as it rounds them.
 * So the four add up to the window but for a step here and there, and for what a part loses where
 * it is louder than 16 bits holds, as on the PC. A mono window goes in as both channels, and its
 * parts come out as the mean of the two (the PC's tool takes stereo windows only).
 *
 * Streamed: the window is read where it lies (a mapped file), a segment at a time, and the parts go
 * to their files as soon as no later segment adds to them. About 55 MB of arrays while it runs,
 * none of them the window whole, on the thread that calls [split], which the model runs on too.
 * At most two segments ever cover a frame (overlap up to 2), so only the overlap is carried.
 */
class WindowSplitter(private val model: SpecModel, private val overlap: Double = OVERLAP) {
    init {
        require(overlap > 1.0 && overlap <= 2.0) { "overlap must be over 1 and at most 2: $overlap" }
    }

    /** The last split's segments, and the time its model runs took: for the log. */
    var segments = 0
        private set
    var modelNanos = 0L
        private set

    /**
     * Splits [window] (frame f's channel c at index f * [channels] + c, from 0 to its limit) at
     * [rate] Hz into [targets]: vocals, drums, bass and melody, each 16-bit interleaved at the
     * window's rate and channels and of its length, written beside them under a temporary name and
     * then put in place. [progress] hears the fraction done after each segment; [cancelled] is
     * asked before each step. Returns true once all four are in place, false when cancelled, with
     * nothing then left behind; the model's and the files' failures are thrown, the same way.
     */
    fun split(
        window: ShortBuffer,
        rate: Int,
        channels: Int,
        targets: List<File>,
        progress: (Double) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean {
        require(channels == 1 || channels == 2) { "a window of $channels channels" }
        require(rate > 0) { "a rate of $rate Hz" }
        require(targets.size == PART_NAMES.size) { "four files: ${PART_NAMES.joinToString()}" }
        val frames = window.limit() / channels
        require(frames > 0) { "an empty window" }
        val down = Resampler(rate, Scnet.RATE)
        val back = Resampler(Scnet.RATE, rate)
        val stretchFrames = down.outputFrames(frames.toLong()).toInt()
        val plan = ScnetPlan(stretchFrames, overlap)
        val temps = targets.map { File(it.path + TEMP) }
        var writer: PartsWriter? = null
        var done = false
        var placed = 0
        segments = plan.count
        modelNanos = 0L
        try {
            for (t in temps) {
                t.parentFile?.mkdirs()
                t.delete()
            }
            val reader = StretchReader(window, channels, frames, down)
            val stft = ScnetStft()
            val spec = FloatArray(Scnet.SPEC_SIZE)
            val inLeft = FloatArray(SEGMENT)
            val inRight = FloatArray(SEGMENT)
            // Vocals, drums and bass of one segment: left then right of each.
            val parts = Array(2 * MODEL_PARTS.size) { FloatArray(SEGMENT) }
            val w = PartsWriter(temps, plan, back, window, frames, channels).also { writer = it }
            progress(0.0)
            for (i in 0 until plan.count) {
                if (cancelled()) return false
                plan.input(i, reader, inLeft, inRight)
                stft.forward(inLeft, inRight, spec)
                val t0 = System.nanoTime()
                model.run(spec)
                modelNanos += System.nanoTime() - t0
                for ((t, stem) in MODEL_PARTS.withIndex()) {
                    if (cancelled()) return false
                    model.readStem(stem, spec)
                    stft.inverse(spec, 0, parts[2 * t], parts[2 * t + 1])
                }
                w.add(i, parts)
                progress((i + 1).toDouble() / plan.count)
            }
            w.finish()
            if (cancelled()) return false
            for (t in temps.indices) {
                if (!temps[t].renameTo(targets[t])) throw IOException("could not put ${targets[t].name} in place")
                placed++
            }
            done = true
            return true
        } finally {
            writer?.close()
            if (!done) {
                temps.forEach { it.delete() }
                // Never a set of four made by two splits.
                if (placed > 0) targets.forEach { it.delete() }
            }
        }
    }

    /**
     * Vocals, drums and bass on their way to their files, and melody made from them: each part's
     * segments added back with the weights [ScnetSum] gives them, in the same float32 steps, so the
     * sums are ScnetSum's to the bit; brought back to the window's rate as soon as a frame is final;
     * melody there the window minus the three, ((window - vocals) - drums) - bass in float32 as
     * numpy takes it in split_windows.py; all four rounded and written.
     */
    private class PartsWriter(
        temps: List<File>,
        private val plan: ScnetPlan,
        back: Resampler,
        private val window: ShortBuffer,
        private val frames: Int,
        private val channels: Int,
    ) : AutoCloseable {
        private val outs = ArrayList<OutputStream>()
        private var open = true

        init {
            try {
                for (t in temps) outs += BufferedOutputStream(FileOutputStream(t), IO_BUFFER)
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        private val rows = 2 * MODEL_PARTS.size

        /** The weighted sums of the previous segment over the frames the next one covers too. */
        private val carry = Array(rows) { FloatArray(plan.border) }
        private var carried = 0
        private val pad = if (plan.padded) plan.border else 0
        private val block = Array(rows) { FloatArray(BLOCK) }
        private var blocked = 0
        private val bytes = Array(PART_NAMES.size) { ByteArray(BLOCK * channels * 2) }
        private val samples = ShortArray(BLOCK * channels)
        private val stream = ResamplerStream(back, rows, plan.frames.toLong()) { from, count, values -> write(from, count, values) }

        /** Segment [i]'s parts: [rows] rows of [Scnet.SEGMENT] samples, each part's left then right. */
        fun add(i: Int, parts: Array<FloatArray>) {
            val s = plan.start(i)
            val n = plan.length(i)
            // Frames before the next segment's start are final once this one is in: the next
            // segment starts a step on, and two segments at most cover a frame.
            val final = if (plan.isLast(i)) n else plan.step
            check(carried <= final)
            for (p in 0 until final) {
                val q = s + p - pad
                if (q < 0 || q >= plan.frames) continue
                val w = plan.weight(i, p)
                if (p < carried) {
                    val d = maxOf(0f + plan.weight(i - 1, p + plan.step) + w, 1e-8f)
                    for (r in 0 until rows) block[r][blocked] = (carry[r][p] + parts[r][p] * w) / d
                } else {
                    val d = maxOf(0f + w, 1e-8f)
                    for (r in 0 until rows) block[r][blocked] = (0f + parts[r][p] * w) / d
                }
                if (++blocked == BLOCK) flush()
            }
            carried = n - final
            for (p in final until n) {
                val w = plan.weight(i, p)
                for (r in 0 until rows) carry[r][p - final] = 0f + parts[r][p] * w
            }
        }

        private fun flush() {
            if (blocked == 0) return
            stream.push(block, 0, blocked)
            blocked = 0
        }

        /** After the last segment: the frames that read past the stretch's end, then the files closed. */
        fun finish() {
            flush()
            stream.finish()
            for (o in outs) o.flush()
            close()
        }

        override fun close() {
            if (!open) return
            open = false
            var failed: IOException? = null
            for (o in outs) {
                try {
                    o.close()
                } catch (e: IOException) {
                    if (failed == null) failed = e
                }
            }
            failed?.let { throw it }
        }

        /**
         * Window frames [from] until [from] + [count] of the three parts (values: vocals left and
         * right, drums, bass), cut at the window's own length as split_windows.py cuts them, with
         * melody made beside them from the window.
         */
        private fun write(from: Long, count: Int, values: Array<FloatArray>) {
            val n = min(count.toLong(), frames - from).toInt()
            var at = 0
            while (at < n) {
                val m = min(BLOCK, n - at)
                write(from + at, at, m, values)
                at += m
            }
        }

        /** [n] frames from window frame [from], the parts' values from index [at]. */
        private fun write(from: Long, at: Int, n: Int, values: Array<FloatArray>) {
            val view = window.duplicate()
            view.position((from * channels).toInt())
            view.get(samples, 0, n * channels)
            var k = 0
            for (f in 0 until n) {
                val o = at + f
                for (c in 0 until channels) {
                    val v: Float
                    val d: Float
                    val b: Float
                    if (channels == 2) {
                        v = values[c][o]
                        d = values[2 + c][o]
                        b = values[4 + c][o]
                    } else {
                        v = (values[0][o] + values[1][o]) * 0.5f
                        d = (values[2][o] + values[3][o]) * 0.5f
                        b = (values[4][o] + values[5][o]) * 0.5f
                    }
                    val m = ((samples[f * channels + c] / 32768f - v) - d) - b
                    put(bytes[0], k, quantize(v))
                    put(bytes[1], k, quantize(d))
                    put(bytes[2], k, quantize(b))
                    put(bytes[3], k, quantize(m))
                    k += 2
                }
            }
            for (t in outs.indices) outs[t].write(bytes[t], 0, k)
        }
    }

    /**
     * The window as the model hears it, at 44.1 kHz, read anywhere: made a block at a time from the
     * window where it lies. A segment reads the stretch in order but for a reflection at each end,
     * and the next one starts by reading the last 2.7 s of it again, so the last 32 blocks (6 s, 2 MB)
     * are kept. A mono window is both channels.
     */
    internal class StretchReader(
        private val window: ShortBuffer,
        private val channels: Int,
        private val frames: Int,
        private val resampler: Resampler,
    ) : Stretch {
        private val length = resampler.outputFrames(frames.toLong())
        private val keys = IntArray(SLOTS) { -1 }
        private val values = Array(SLOTS) { arrayOf(FloatArray(BLOCK), FloatArray(BLOCK)) }
        private val most = ((BLOCK.toLong() * resampler.down + resampler.up - 1) / resampler.up).toInt() + resampler.span + resampler.down + 2
        private val shorts = ShortArray(most * channels)
        private val inLeft = FloatArray(most)
        private val inRight = FloatArray(most)

        override fun sample(channel: Int, frame: Int): Float {
            val b = frame ushr SHIFT
            val slot = b and (SLOTS - 1)
            if (keys[slot] != b) fill(b, slot)
            return values[slot][channel][frame and (BLOCK - 1)]
        }

        private fun fill(b: Int, slot: Int) {
            val from = b.toLong() shl SHIFT
            val count = min(BLOCK.toLong(), length - from).toInt()
            require(count > 0) { "frame $from past the stretch's $length" }
            val lo = max(0L, resampler.firstInput(from)).toInt()
            val hi = min(frames.toLong(), resampler.endInput(from + count - 1)).toInt()
            val n = max(0, hi - lo)
            check(n <= most)
            if (n > 0) {
                val view = window.duplicate()
                view.position(lo * channels)
                view.get(shorts, 0, n * channels)
            }
            for (f in 0 until n) {
                val l = shorts[f * channels] / 32768f
                inLeft[f] = l
                inRight[f] = if (channels == 2) shorts[f * channels + 1] / 32768f else l
            }
            resampler.resample(inLeft, lo.toLong(), n, from, count, values[slot][0], 0)
            resampler.resample(inRight, lo.toLong(), n, from, count, values[slot][1], 0)
            keys[slot] = b
        }

        private companion object {
            const val SHIFT = 13
            const val BLOCK = 1 shl SHIFT
            const val SLOTS = 32
        }
    }

    companion object {
        /** Overlap 1.33, picked by ear on 20 Oct 2026: a third of the reference's work (PARTS-NOW.md). */
        const val OVERLAP = 1.33

        /** The files' parts in order, as the editor's parts store names them. */
        val PART_NAMES = listOf("vocals", "drums", "bass", "melody")

        /** The model's stem for each of the first three ([Scnet.STEMS]: drums, bass, other, vocals). */
        private val MODEL_PARTS = intArrayOf(3, 0, 1)

        /** Beside a file while it is written. */
        const val TEMP = ".part"

        private const val BLOCK = 4096
        private const val IO_BUFFER = 1 shl 16

        /** A part's sample as split_windows.py rounds it: clipped to 16 bits, then to the nearest step, halves to even. */
        internal fun quantize(v: Float): Int = Math.rint((v.coerceIn(-1f, MAX) * 32768f).toDouble()).toInt()

        private const val MAX = 32767f / 32768f

        private fun put(bytes: ByteArray, k: Int, s: Int) {
            bytes[k] = s.toByte()
            bytes[k + 1] = (s shr 8).toByte()
        }
    }
}
