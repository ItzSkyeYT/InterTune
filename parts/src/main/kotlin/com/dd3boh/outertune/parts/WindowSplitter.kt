/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.SEGMENT
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A window of a song split into its parts on the phone, the way the PC splits it
 * (experiments/features/automix/parts/tools/split_windows.py): the window, 16-bit and interleaved
 * at its own rate in one or two channels, brought to 44.1 kHz ([Resampler], torchaudio's filter),
 * through SCNet segment by segment at [overlap] ([ScnetPlan], added back with [ScnetSum]'s weights
 * to the bit), vocals, drums and bass brought back to the window's rate and rounded to 16 bits as
 * the PC rounds them, and melody the window minus those three, so the four add up to the window
 * exactly wherever nothing clips.
 *
 * Streamed: the window is read where it lies (a mapped file), a segment at a time, and each part
 * goes to its file as soon as no later segment adds to it. About 40 MB of arrays while it runs,
 * none of them the window whole, on the thread that calls [split], which the model runs on too.
 * At most two segments ever cover a frame (overlap up to 2), so only the overlap is carried.
 */
class WindowSplitter(private val model: SpecModel, private val overlap: Double = OVERLAP) {
    init {
        require(overlap > 1.0 && overlap <= 2.0) { "overlap must be over 1 and at most 2: $overlap" }
    }

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
        require(targets.size == PART_NAMES.size) { "four files: ${PART_NAMES.joinToString()}" }
        val frames = window.limit() / channels
        require(frames > 0) { "an empty window" }
        val down = Resampler(rate, Scnet.RATE)
        val back = Resampler(Scnet.RATE, rate)
        val stretchFrames = down.outputFrames(frames.toLong()).toInt()
        val plan = ScnetPlan(stretchFrames, overlap)
        val temps = targets.map { File(it.path + TEMP) }
        val writers = ArrayList<PartWriter>()
        var done = false
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
            val left = FloatArray(SEGMENT)
            val right = FloatArray(SEGMENT)
            for (t in MODEL_PARTS.indices) writers += PartWriter(temps[t], plan, back, frames, channels)
            progress(0.0)
            for (i in 0 until plan.count) {
                if (cancelled()) return false
                plan.input(i, reader, inLeft, inRight)
                stft.forward(inLeft, inRight, spec)
                model.run(spec)
                for ((t, stem) in MODEL_PARTS.withIndex()) {
                    if (cancelled()) return false
                    model.readStem(stem, spec)
                    stft.inverse(spec, 0, left, right)
                    writers[t].add(i, left, right)
                }
                progress((i + 1).toDouble() / plan.count)
            }
            for (w in writers) w.finish()
            if (cancelled()) return false
            if (!writeMelody(window, channels, frames, temps, cancelled)) return false
            for (t in temps.indices) {
                if (!temps[t].renameTo(targets[t])) throw IOException("could not put ${targets[t].name} in place")
            }
            done = true
            return true
        } finally {
            for (w in writers) w.close()
            if (!done) temps.forEach { it.delete() }
        }
    }

    /**
     * One of the model's parts on its way to its file: its segments added back with the weights
     * [ScnetSum] gives them, in the same float32 steps, so the sum is ScnetSum's to the bit; brought
     * back to the window's rate as soon as each frame is final; rounded and written.
     */
    private class PartWriter(file: File, private val plan: ScnetPlan, back: Resampler, private val frames: Int, private val channels: Int) {
        private val out: OutputStream = BufferedOutputStream(FileOutputStream(file), IO_BUFFER)
        private var open = true

        /** The weighted sum of the previous segment over the frames the next one covers too. */
        private val carryLeft = FloatArray(plan.border)
        private val carryRight = FloatArray(plan.border)
        private var carried = 0
        private val pad = if (plan.padded) plan.border else 0
        private val block = arrayOf(FloatArray(BLOCK), FloatArray(BLOCK))
        private var blocked = 0
        private val bytes = ByteArray(BLOCK * 2 * 2)
        private val stream = ResamplerStream(back, 2, plan.frames.toLong()) { from, count, values -> write(from, count, values) }

        /** Segment [i]'s part, [Scnet.SEGMENT] samples a channel. */
        fun add(i: Int, left: FloatArray, right: FloatArray) {
            val s = plan.start(i)
            val n = plan.length(i)
            // Frames before the next segment's start are final once this one is in: the next
            // segment starts a step on, and two segments at most cover a frame.
            val final = if (plan.isLast(i)) n else plan.step
            check(carried <= final)
            for (p in 0 until final) {
                val w = plan.weight(i, p)
                val l: Float
                val r: Float
                if (p < carried) {
                    val d = maxOf(0f + plan.weight(i - 1, p + plan.step) + w, 1e-8f)
                    l = (carryLeft[p] + left[p] * w) / d
                    r = (carryRight[p] + right[p] * w) / d
                } else {
                    val d = maxOf(0f + w, 1e-8f)
                    l = (0f + left[p] * w) / d
                    r = (0f + right[p] * w) / d
                }
                val q = s + p - pad
                if (q >= 0 && q < plan.frames) emit(l, r)
            }
            carried = n - final
            for (p in final until n) {
                val w = plan.weight(i, p)
                carryLeft[p - final] = 0f + left[p] * w
                carryRight[p - final] = 0f + right[p] * w
            }
        }

        private fun emit(l: Float, r: Float) {
            block[0][blocked] = l
            block[1][blocked] = r
            if (++blocked == BLOCK) flush()
        }

        private fun flush() {
            if (blocked == 0) return
            stream.push(block, 0, blocked)
            blocked = 0
        }

        /** After the last segment: the frames that read past the stretch's end, then the file closed. */
        fun finish() {
            flush()
            stream.finish()
            close()
        }

        fun close() {
            if (!open) return
            open = false
            out.close()
        }

        /** Window frames [from] until [from] + [count], cut at the window's own length, as split_windows.py cuts them. */
        private fun write(from: Long, count: Int, values: Array<FloatArray>) {
            val n = min(count.toLong(), frames - from).toInt()
            if (n <= 0) return
            var k = 0
            for (o in 0 until n) {
                if (channels == 2) {
                    k = put(bytes, k, quantize(values[0][o]))
                    k = put(bytes, k, quantize(values[1][o]))
                } else {
                    k = put(bytes, k, quantize((values[0][o] + values[1][o]) * 0.5f))
                }
                if (k == bytes.size) {
                    out.write(bytes, 0, k)
                    k = 0
                }
            }
            if (k > 0) out.write(bytes, 0, k)
        }
    }

    /**
     * The window as the model hears it, at 44.1 kHz, read anywhere: made a block at a time from the
     * window where it lies, the last few blocks kept, since a segment reads the stretch nearly in
     * order, with a reflection at each end. A mono window is both channels.
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
            require(count > 0) { "frame ${from} past the stretch's $length" }
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
            const val SLOTS = 4
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

        private fun put(bytes: ByteArray, k: Int, s: Int): Int {
            bytes[k] = s.toByte()
            bytes[k + 1] = (s shr 8).toByte()
            return k + 2
        }

        /**
         * The melody: the window minus the three parts as they were written, sample by sample, so the
         * four add up to the window exactly, but where that runs past 16 bits.
         */
        private fun writeMelody(window: ShortBuffer, channels: Int, frames: Int, temps: List<File>, cancelled: () -> Boolean): Boolean {
            val samples = frames.toLong() * channels
            for (t in 0 until 3) check(temps[t].length() == samples * 2) { "${temps[t].name} is ${temps[t].length()} bytes, not ${samples * 2}" }
            val inputs = (0 until 3).map { BufferedInputStream(FileInputStream(temps[it]), IO_BUFFER) }
            try {
                BufferedOutputStream(FileOutputStream(temps[3]), IO_BUFFER).use { out ->
                    val w = ShortArray(BLOCK)
                    val parts = Array(3) { ByteArray(BLOCK * 2) }
                    val bytes = ByteArray(BLOCK * 2)
                    val view = window.duplicate()
                    view.position(0)
                    var at = 0L
                    while (at < samples) {
                        if (cancelled()) return false
                        val n = min(BLOCK.toLong(), samples - at).toInt()
                        view.get(w, 0, n)
                        for (t in 0 until 3) readFully(inputs[t], parts[t], n * 2)
                        var k = 0
                        for (i in 0 until n) {
                            var m = w[i].toInt()
                            for (t in 0 until 3) m -= (parts[t][2 * i].toInt() and 0xff) or (parts[t][2 * i + 1].toInt() shl 8)
                            k = put(bytes, k, m.coerceIn(-32768, 32767))
                        }
                        out.write(bytes, 0, k)
                        at += n
                    }
                }
            } finally {
                inputs.forEach { it.close() }
            }
            return true
        }

        private fun readFully(input: InputStream, into: ByteArray, count: Int) {
            var got = 0
            while (got < count) {
                val n = input.read(into, got, count - got)
                if (n < 0) throw IOException("a part's file ended early")
                got += n
            }
        }
    }
}
