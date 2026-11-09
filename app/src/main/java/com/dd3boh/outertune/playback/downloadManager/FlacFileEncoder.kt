/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.constants.DownloadFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A FLAC file (Unreleased.DOWNLOAD_FORMATS), written here rather than by the phone's FLAC encoder,
 * which not every phone has (Android 7, the app's lowest, does not) and which cannot go back to say
 * how long the file turned out to be: this one writes the stream info at the end, when it knows, with
 * the audio's MD5, so that a player knows the length and a tool can check the file.
 *
 * Small and plain, as the format allows: blocks of 4096 samples, stereo as the best of left/right,
 * left/side, side/right and mid/side, and for each channel the best of a constant, the samples
 * as they are, a fixed predictor of order 0 to 4, or a linear predictor of order 8 or 12 (windowed
 * autocorrelation, Levinson-Durbin, coefficients of 12 bits), with its residual in Rice codes cut
 * into the best number of partitions. Always 16 bits a sample, mono or stereo, which is what the
 * decoder gives.
 *
 * The tags are a Vorbis comment block and the cover a picture block, written before the audio, the
 * link before the cover. No Android in here, so it is tested on the JVM, against a decoder of its own
 * in the tests and, on the PC, against flac -t.
 */
internal class FlacFileEncoder(override val file: File, private val tags: SongTags) : FileEncoder {
    override val format = DownloadFormat.FLAC

    private var out: OutputStream? = null
    private var rate = 0
    private var channels = 0
    private val left = IntArray(BLOCK)
    private val right = IntArray(BLOCK)
    private var pending = 0
    private var frameNumber = 0L
    private var totalSamples = 0L
    private var lastBlock = 0
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private val md5 = MessageDigest.getInstance("MD5")
    private var pcm = ByteArray(0)
    private val frame = Bits()
    private val side = IntArray(BLOCK)
    private val mid = IntArray(BLOCK)
    private val residuals = Array(MAX_ORDER + 1) { IntArray(BLOCK) }

    override fun start(rate: Int, channels: Int) {
        require(channels in 1..2) { "a FLAC of $channels channels" }
        require(rate in 1..655_350) { "a FLAC at $rate Hz" }
        this.rate = rate
        this.channels = channels
        out = BufferedOutputStream(FileOutputStream(file), 64 * 1024).also { it.write(metadata()) }
    }

    override fun add(samples: ShortArray, frames: Int) {
        if (frames <= 0) return
        val count = frames * channels
        if (pcm.size < count * 2) pcm = ByteArray(count * 2)
        for (i in 0 until count) {
            pcm[2 * i] = samples[i].toInt().toByte()
            pcm[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        md5.update(pcm, 0, count * 2)
        for (f in 0 until frames) {
            left[pending] = samples[f * channels].toInt()
            if (channels == 2) right[pending] = samples[f * channels + 1].toInt()
            if (++pending == BLOCK) encodeBlock(BLOCK)
        }
    }

    override fun finish() {
        if (pending > 0) encodeBlock(pending)
        if (totalSamples == 0L) throw IOException("a FLAC with no audio")
        out!!.flush()
        out!!.close()
        out = null
        // Now that it is known: the sizes, the length and the MD5 of the audio, in the stream info.
        val blocks = frameNumber
        val minBlock = if (blocks >= 2) BLOCK else lastBlock
        val maxBlock = if (blocks >= 2) BLOCK else lastBlock
        val info = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
        info.putShort(minBlock.toShort()).putShort(maxBlock.toShort())
        info.put((minFrame shr 16).toByte()).put((minFrame shr 8).toByte()).put(minFrame.toByte())
        info.put((maxFrame shr 16).toByte()).put((maxFrame shr 8).toByte()).put(maxFrame.toByte())
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((BITS - 1).toLong() shl 36) or (totalSamples and 0xFFFFFFFFFL)
        info.putLong(packed)
        info.put(md5.digest())
        RandomAccessFile(file, "rw").use {
            it.seek(8)   // the marker, then the block's own header
            it.write(info.array())
        }
    }

    override fun close() {
        runCatching { out?.close() }
        out = null
    }

    // ---- the metadata

    /** The marker and the blocks before the audio: stream info (filled in at the end), comments, the picture. */
    private fun metadata(): ByteArray {
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        blocks += 0 to ByteArray(34)
        blocks += 4 to vorbisComments()
        tags.cover?.flacPictureBlock()?.takeIf { it.size < (1 shl 24) }?.let { blocks += 6 to it }
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        for ((i, block) in blocks.withIndex()) {
            val last = if (i == blocks.lastIndex) 0x80 else 0
            out.write(last or block.first)
            out.write(block.second.size shr 16); out.write(block.second.size shr 8); out.write(block.second.size)
            out.write(block.second)
        }
        return out.toByteArray()
    }

    private fun vorbisComments(): ByteArray {
        val comments = tags.vorbisComments(withPicture = false).map { it.toByteArray(Charsets.UTF_8) }
        val vendor = "InterTune".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + vendor.size + comments.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(vendor.size).put(vendor).putInt(comments.size)
        for (c in comments) buffer.putInt(c.size).put(c)
        return buffer.array()
    }

    // ---- a block

    private fun encodeBlock(n: Int) {
        totalSamples += n
        lastBlock = n
        val w = frame
        w.reset()
        // The frame header.
        w.put(0x3FFE, 14); w.put(0, 1); w.put(0, 1)   // sync, reserved, a fixed block size
        val blockCode = blockCode(n)
        w.put(blockCode, 4); w.put(rateCode(rate), 4)

        val plans: Array<Plan>
        val assignment: Int
        if (channels == 1) {
            plans = arrayOf(analyse(left, n, BITS))
            assignment = 0
        } else {
            for (i in 0 until n) {
                side[i] = left[i] - right[i]
                mid[i] = (left[i] + right[i]) shr 1
            }
            val l = analyse(left, n, BITS)
            val r = analyse(right, n, BITS)
            val s = analyse(side, n, BITS + 1)
            val m = analyse(mid, n, BITS)
            val choices = longArrayOf(l.bits + r.bits, l.bits + s.bits, s.bits + r.bits, m.bits + s.bits)
            val best = choices.indices.minBy { choices[it] }
            assignment = intArrayOf(1, 8, 9, 10)[best]
            plans = when (best) {
                0 -> arrayOf(l, r)
                1 -> arrayOf(l, s)
                2 -> arrayOf(s, r)
                else -> arrayOf(m, s)
            }
        }
        w.put(assignment, 4); w.put(4, 3); w.put(0, 1)   // channels, 16 bits, reserved
        utf8(w, frameNumber)
        if (blockCode == 6) w.put(n - 1, 8) else if (blockCode == 7) w.put(n - 1, 16)
        w.put(crc8(w.bytes, w.byteCount), 8)

        if (channels == 1) {
            write(w, left, n, plans[0], BITS)
        } else {
            val first = when (assignment) { 1, 8 -> left; 9 -> side; else -> mid }
            val second = when (assignment) { 1, 9 -> right; else -> side }
            write(w, first, n, plans[0], if (assignment == 9) BITS + 1 else BITS)
            write(w, second, n, plans[1], if (assignment == 8 || assignment == 10) BITS + 1 else BITS)
        }
        w.alignZero()
        w.put(crc16(w.bytes, w.byteCount), 16)
        out!!.write(w.bytes, 0, w.byteCount)
        minFrame = minOf(minFrame, w.byteCount)
        maxFrame = maxOf(maxFrame, w.byteCount)
        frameNumber++
        pending = 0
    }

    private fun blockCode(n: Int): Int = when (n) {
        192 -> 1; 576 -> 2; 1152 -> 3; 2304 -> 4; 4608 -> 5
        256 -> 8; 512 -> 9; 1024 -> 10; 2048 -> 11; 4096 -> 12; 8192 -> 13; 16384 -> 14; 32768 -> 15
        else -> if (n - 1 < 256) 6 else 7
    }

    private fun rateCode(rate: Int): Int = when (rate) {
        88_200 -> 1; 176_400 -> 2; 192_000 -> 3; 8_000 -> 4; 16_000 -> 5; 22_050 -> 6; 24_000 -> 7
        32_000 -> 8; 44_100 -> 9; 48_000 -> 10; 96_000 -> 11
        else -> 0   // the stream info has it
    }

    /** The frame number as UTF-8 codes a number, up to 36 bits. */
    private fun utf8(w: Bits, value: Long) {
        if (value < 0x80) { w.put(value.toInt(), 8); return }
        var extra = 1
        while (extra < 6 && value >= (1L shl (5 * extra + 6))) extra++
        w.put(((0xFF00 shr (extra + 1)) and 0xFF) or (value shr (6 * extra)).toInt(), 8)
        for (i in extra - 1 downTo 0) w.put(0x80 or ((value shr (6 * i)) and 0x3F).toInt(), 8)
    }

    // ---- a channel

    private class Plan(val kind: Int, val order: Int, val bits: Long, val partitionOrder: Int) {
        var constant = 0
        var residual = IntArray(0)
        var params = IntArray(0)

        /** For a linear predictor: its coefficients as the file holds them, and the shift they are read with. */
        var coefficients = IntArray(0)
        var shift = 0
    }

    /** The cheapest way to write [n] samples of [bps] bits: constant, as they are, or a fixed predictor of order 0 to 4. */
    private fun analyse(x: IntArray, n: Int, bps: Int): Plan {
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) return Plan(CONSTANT, 0, SUBFRAME_HEADER + bps.toLong(), 0).also { it.constant = x[0] }
        var best = Plan(VERBATIM, 0, SUBFRAME_HEADER + n.toLong() * bps, 0)
        for (order in 0..minOf(MAX_ORDER, n - 1)) {
            val e = residuals[order]
            residual(x, n, order, e)
            val count = n - order
            val partition = partition(e, count, n, order)
            val total = SUBFRAME_HEADER + order.toLong() * bps + 2 + 4 + partition.bits
            if (total < best.bits) {
                best = Plan(FIXED, order, total, partition.order).also {
                    it.residual = e.copyOf(count)
                    it.params = partition.params
                }
            }
        }
        // A linear predictor, where the block is long enough to be worth its coefficients.
        if (n >= MIN_LPC_BLOCK) {
            val window = window(n)
            val windowed = DoubleArray(n) { x[it] * window[it] }
            val coefficients = levinson(windowed, MAX_LPC_ORDER)
            for (order in LPC_ORDERS) {
                val lp = coefficients[order] ?: continue
                val (quantized, shift) = quantize(lp, order) ?: continue
                val e = residuals[0]
                lpcResidual(x, n, quantized, shift, e)
                val count = n - order
                val partition = partition(e, count, n, order)
                // Header, warm-up, then the precision, the shift and the coefficients, then the residual's own header.
                val total = SUBFRAME_HEADER + order.toLong() * bps + 4 + 5 + order.toLong() * LPC_PRECISION + 2 + 4 + partition.bits
                if (total < best.bits) {
                    best = Plan(LPC, order, total, partition.order).also {
                        it.residual = e.copyOf(count)
                        it.params = partition.params
                        it.coefficients = quantized
                        it.shift = shift
                    }
                }
            }
        }
        return best
    }

    private var windowOf = DoubleArray(0)

    /** Tukey(0.5), the window libFLAC's default levels use: a cosine taper over a quarter of the block at each end. */
    private fun window(n: Int): DoubleArray {
        if (windowOf.size == n) return windowOf
        val taper = (n * 0.25).toInt().coerceAtLeast(1)
        windowOf = DoubleArray(n) { i ->
            when {
                i < taper -> 0.5 - 0.5 * Math.cos(Math.PI * i / taper)
                i >= n - taper -> 0.5 - 0.5 * Math.cos(Math.PI * (n - 1 - i) / taper)
                else -> 1.0
            }
        }
        return windowOf
    }

    /**
     * Predictor coefficients for every order up to [maxOrder] from the autocorrelation of [x], by
     * Levinson-Durbin. Index 0 is unused; an order whose recursion went unstable is null.
     */
    private fun levinson(x: DoubleArray, maxOrder: Int): Array<DoubleArray?> {
        val r = DoubleArray(maxOrder + 1)
        for (lag in 0..maxOrder) {
            var sum = 0.0
            for (i in lag until x.size) sum += x[i] * x[i - lag]
            r[lag] = sum
        }
        val out = arrayOfNulls<DoubleArray>(maxOrder + 1)
        if (r[0] <= 0.0) return out
        var error = r[0] * (1.0 + 1e-9)
        var a = DoubleArray(0)
        for (order in 1..maxOrder) {
            var acc = r[order]
            for (j in 0 until order - 1) acc -= a[j] * r[order - 1 - j]
            val k = acc / error
            val next = DoubleArray(order)
            for (j in 0 until order - 1) next[j] = a[j] - k * a[order - 2 - j]
            next[order - 1] = k
            error *= 1.0 - k * k
            if (error <= 0.0 || next.any { it.isNaN() }) break
            a = next
            out[order] = next
        }
        return out
    }

    /** The coefficients as integers of [LPC_PRECISION] bits and the shift that scales them back, or null if they will not fit. */
    private fun quantize(lp: DoubleArray, order: Int): Pair<IntArray, Int>? {
        var cmax = 0.0
        for (c in lp) cmax = maxOf(cmax, Math.abs(c))
        if (cmax <= 0.0 || cmax.isNaN()) return null
        // The largest coefficient takes the full range: its exponent sets the shift.
        val exponent = Math.getExponent(cmax) + 1
        val shift = (LPC_PRECISION - 1 - exponent).coerceAtMost(MAX_SHIFT)
        if (shift < 0) return null
        val limit = (1 shl (LPC_PRECISION - 1)) - 1
        val q = IntArray(order)
        var error = 0.0
        for (i in 0 until order) {
            error += lp[i] * (1L shl shift)
            val rounded = Math.round(error).toInt().coerceIn(-limit - 1, limit)
            q[i] = rounded
            error -= rounded
        }
        return q to shift
    }

    private fun lpcResidual(x: IntArray, n: Int, q: IntArray, shift: Int, e: IntArray) {
        val order = q.size
        for (i in order until n) {
            var sum = 0L
            for (j in 0 until order) sum += q[j].toLong() * x[i - 1 - j]
            e[i - order] = x[i] - (sum shr shift).toInt()
        }
    }

    private fun residual(x: IntArray, n: Int, order: Int, e: IntArray) {
        when (order) {
            0 -> for (i in 0 until n) e[i] = x[i]
            1 -> for (i in 1 until n) e[i - 1] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) e[i - 2] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) e[i - 3] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) e[i - 4] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    private class Partition(val bits: Long, val order: Int, val params: IntArray)

    /** The best number of Rice partitions for [count] residuals of a block of [n], and each one's parameter. */
    private fun partition(e: IntArray, count: Int, n: Int, order: Int): Partition {
        var maxOrder = 0
        while (maxOrder < MAX_PARTITION_ORDER && n % (1 shl (maxOrder + 1)) == 0 && (n shr (maxOrder + 1)) > order) maxOrder++
        // The sums of the folded residuals in the finest partitions; coarser ones are sums of pairs.
        var sums = LongArray(1 shl maxOrder)
        var counts = IntArray(1 shl maxOrder)
        var at = 0
        for (p in sums.indices) {
            val size = (n shr maxOrder) - if (p == 0) order else 0
            var sum = 0L
            for (i in at until at + size) sum += fold(e[i])
            sums[p] = sum
            counts[p] = size
            at += size
        }
        var best: Partition? = null
        var level = maxOrder
        while (true) {
            var bits = 0L
            val params = IntArray(sums.size)
            for (p in sums.indices) {
                val (k, cost) = rice(counts[p], sums[p])
                params[p] = k
                bits += 4 + cost
            }
            if (best == null || bits < best.bits) best = Partition(bits, level, params)
            if (level == 0) break
            val merged = LongArray(sums.size / 2)
            val mergedCounts = IntArray(sums.size / 2)
            for (p in merged.indices) {
                merged[p] = sums[2 * p] + sums[2 * p + 1]
                mergedCounts[p] = counts[2 * p] + counts[2 * p + 1]
            }
            sums = merged
            counts = mergedCounts
            level--
        }
        return best!!
    }

    /** The Rice parameter that costs least for [count] values that fold to [sum] in all, and its cost in bits. */
    private fun rice(count: Int, sum: Long): Pair<Int, Long> {
        if (count == 0) return 0 to 0L
        val mean = sum / count
        var guess = 0
        while (guess < MAX_RICE && (1L shl (guess + 1)) <= mean) guess++
        var bestK = guess
        var bestCost = Long.MAX_VALUE
        for (k in maxOf(0, guess - 1)..minOf(MAX_RICE, guess + 1)) {
            val cost = count.toLong() * (k + 1) + (sum shr k)
            if (cost < bestCost) { bestCost = cost; bestK = k }
        }
        return bestK to bestCost
    }

    private fun fold(v: Int): Int = (v shl 1) xor (v shr 31)

    private fun write(w: Bits, x: IntArray, n: Int, plan: Plan, bps: Int) {
        w.put(0, 1)
        when (plan.kind) {
            CONSTANT -> {
                w.put(0, 6); w.put(0, 1)
                w.put(plan.constant and mask(bps), bps)
            }
            VERBATIM -> {
                w.put(1, 6); w.put(0, 1)
                for (i in 0 until n) w.put(x[i] and mask(bps), bps)
            }
            else -> {
                if (plan.kind == LPC) w.put(32 + plan.order - 1, 6) else w.put(8 + plan.order, 6)
                w.put(0, 1)
                for (i in 0 until plan.order) w.put(x[i] and mask(bps), bps)
                if (plan.kind == LPC) {
                    w.put(LPC_PRECISION - 1, 4)
                    w.put(plan.shift, 5)
                    for (c in plan.coefficients) w.put(c and mask(LPC_PRECISION), LPC_PRECISION)
                }
                w.put(0, 2)                       // Rice codes with 4-bit parameters
                w.put(plan.partitionOrder, 4)
                val parts = 1 shl plan.partitionOrder
                var at = 0
                for (p in 0 until parts) {
                    val size = (n shr plan.partitionOrder) - if (p == 0) plan.order else 0
                    val k = plan.params[p]
                    w.put(k, 4)
                    for (i in at until at + size) {
                        val u = fold(plan.residual[i])
                        w.zeros(u ushr k)
                        w.put(1, 1)
                        if (k > 0) w.put(u and ((1 shl k) - 1), k)
                    }
                    at += size
                }
            }
        }
    }

    private fun mask(bits: Int) = (1 shl bits) - 1

    /** Bits written most significant first, into bytes. */
    private class Bits {
        var bytes = ByteArray(32 * 1024)
        var byteCount = 0
        private var acc = 0L
        private var accBits = 0

        fun reset() { byteCount = 0; acc = 0; accBits = 0 }

        fun put(value: Int, count: Int) {
            if (count == 0) return
            acc = (acc shl count) or (value.toLong() and ((1L shl count) - 1))
            accBits += count
            while (accBits >= 8) {
                accBits -= 8
                push((acc shr accBits).toByte())
            }
            acc = acc and ((1L shl accBits) - 1)
        }

        fun zeros(count: Int) {
            var left = count
            while (left > 0) {
                val n = minOf(left, 24)
                put(0, n)
                left -= n
            }
        }

        fun alignZero() { if (accBits > 0) put(0, 8 - accBits) }

        private fun push(b: Byte) {
            if (byteCount == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[byteCount++] = b
        }
    }

    private companion object {
        const val BLOCK = 4096
        const val BITS = 16
        const val MAX_ORDER = 4
        const val MAX_PARTITION_ORDER = 8
        const val MAX_RICE = 14

        const val CONSTANT = 0
        const val VERBATIM = 1
        const val FIXED = 2
        const val LPC = 3

        /** Orders of the linear predictors tried, the longest the Levinson recursion goes to, and what they need to be worth it. */
        val LPC_ORDERS = intArrayOf(8, 12)
        const val MAX_LPC_ORDER = 12
        const val MIN_LPC_BLOCK = 256
        const val LPC_PRECISION = 12
        const val MAX_SHIFT = 15

        /** A subframe's header: the zero bit, the six bits of its type, the wasted bits flag. */
        const val SUBFRAME_HEADER = 8L

        private val CRC8 = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
            c and 0xFF
        }

        private val CRC16 = IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
            c and 0xFFFF
        }

        fun crc8(b: ByteArray, count: Int): Int {
            var c = 0
            for (i in 0 until count) c = CRC8[c xor (b[i].toInt() and 0xFF)]
            return c
        }

        fun crc16(b: ByteArray, count: Int): Int {
            var c = 0
            for (i in 0 until count) c = ((c shl 8) and 0xFFFF) xor CRC16[(c shr 8) xor (b[i].toInt() and 0xFF)]
            return c
        }
    }
}
