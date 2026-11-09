/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * A FLAC file read back, for FileEncodersTest: the metadata blocks and every frame, with the checks
 * a real decoder makes (the header's CRC-8, the frame's CRC-16, the sizes the stream info promises).
 * It reads what the app's writer makes (fixed predictors and Rice codes, constant and verbatim
 * subframes, any channel assignment) and nothing it does not; a file it cannot read fails the test.
 */
internal class FlacDecoder(private val b: ByteArray) {
    var rate = 0
    var channels = 0
    var bitsPerSample = 0
    var totalSamples = 0L
    var minBlock = 0
    var maxBlock = 0
    var minFrame = 0
    var maxFrame = 0
    var md5 = ByteArray(16)
    val blockTypes = mutableListOf<Int>()
    val comments = mutableListOf<String>()
    var pictureType = -1
    var pictureMime = ""
    var pictureWidth = 0
    val frameSizes = mutableListOf<Int>()

    /** How many subframes were linear predictors, and how many fixed ones: what the writer chose. */
    var lpcSubframes = 0
    var fixedSubframes = 0
    val samples: ShortArray

    init {
        check(String(b, 0, 4, Charsets.ISO_8859_1) == "fLaC") { "no fLaC marker" }
        var pos = 4
        while (true) {
            val last = b[pos].toInt() and 0x80 != 0
            val type = b[pos].toInt() and 0x7F
            val length = u24(pos + 1)
            val body = pos + 4
            blockTypes += type
            when (type) {
                0 -> streamInfo(body, length)
                4 -> vorbis(body, length)
                6 -> picture(body)
            }
            pos = body + length
            if (last) break
        }
        val collected = ShortArrayBuilder()
        while (pos < b.size) {
            val start = pos
            pos = frame(pos, collected)
            frameSizes += pos - start
        }
        val total = (collected.size / channels).toLong()
        check(total == totalSamples) { "the frames hold $total samples, the stream info says $totalSamples" }
        samples = collected.toArray()
    }

    private class ShortArrayBuilder {
        var data = ShortArray(1 shl 16)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v.toShort()
        }
        fun toArray() = data.copyOf(size)
    }

    private fun u24(at: Int) = ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)
    private fun u32be(at: Int) = (u24(at) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun le32(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun streamInfo(at: Int, length: Int) {
        check(length == 34) { "a stream info of $length bytes" }
        minBlock = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        maxBlock = ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        minFrame = u24(at + 4)
        maxFrame = u24(at + 7)
        var packed = 0L
        for (i in 0 until 8) packed = (packed shl 8) or (b[at + 10 + i].toLong() and 0xFF)
        rate = (packed ushr 44).toInt()
        channels = ((packed ushr 41) and 7).toInt() + 1
        bitsPerSample = ((packed ushr 36) and 31).toInt() + 1
        totalSamples = packed and 0xFFFFFFFFFL
        md5 = b.copyOfRange(at + 18, at + 34)
    }

    private fun vorbis(at: Int, length: Int) {
        var p = at
        val vendor = le32(p); p += 4 + vendor
        val count = le32(p); p += 4
        repeat(count) {
            val n = le32(p); p += 4
            comments += String(b, p, n, Charsets.UTF_8)
            p += n
        }
        check(p == at + length) { "the comment block is $length bytes, its comments end at ${p - at}" }
    }

    private fun picture(at: Int) {
        pictureType = u32be(at)
        val mime = u32be(at + 4)
        pictureMime = String(b, at + 8, mime, Charsets.US_ASCII)
        val desc = u32be(at + 8 + mime)
        pictureWidth = u32be(at + 12 + mime + desc)
    }

    /** Reads bits, most significant first, from [start]. */
    private inner class Bits(start: Int) {
        var byte = start
        var bit = 0
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((b[byte].toInt() shr (7 - bit)) and 1)
                if (++bit == 8) { bit = 0; byte++ }
            }
            return v
        }
        fun signed(n: Int): Int = if (n == 0) 0 else (read(n) shl (32 - n)) shr (32 - n)
        fun unary(): Int {
            var q = 0
            while (read(1) == 0) q++
            return q
        }
        fun align() { if (bit != 0) { bit = 0; byte++ } }
    }

    private fun crc8(from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1; crc = crc and 0xFF }
        }
        return crc
    }

    private fun crc16(from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x8005 else crc shl 1; crc = crc and 0xFFFF }
        }
        return crc
    }

    /** Decodes the frame at [start] into [out], and returns where the next one starts. */
    private fun frame(start: Int, out: ShortArrayBuilder): Int {
        val r = Bits(start)
        check(r.read(14) == 0x3FFE) { "no sync at $start" }
        check(r.read(1) == 0)
        check(r.read(1) == 0) { "a variable block size" }
        val blockCode = r.read(4)
        val rateCode = r.read(4)
        val channelCode = r.read(4)
        val sizeCode = r.read(3)
        check(r.read(1) == 0)
        check(sizeCode == 4) { "not 16 bits: $sizeCode" }
        // The frame number, in the form UTF-8 gives a number.
        val first = r.read(8)
        val extra = when { first < 0x80 -> 0; first < 0xE0 -> 1; first < 0xF0 -> 2; first < 0xF8 -> 3; first < 0xFC -> 4; else -> 5 }
        repeat(extra) { check(r.read(8) shr 6 == 2) }
        val blockSize = when (blockCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockCode - 2)
            6 -> r.read(8) + 1
            7 -> r.read(16) + 1
            in 8..15 -> 256 shl (blockCode - 8)
            else -> error("block size code $blockCode")
        }
        when (rateCode) { 12 -> r.read(8); 13, 14 -> r.read(16) }
        val headerEnd = r.byte
        check(r.bit == 0)
        check(r.read(8) == crc8(start, headerEnd)) { "header CRC-8 at $start" }
        val count = if (channelCode < 8) channelCode + 1 else 2
        val columns = Array(count) { IntArray(blockSize) }
        for (c in 0 until count) {
            val side = (channelCode == 8 && c == 1) || (channelCode == 9 && c == 0) || (channelCode == 10 && c == 1)
            subframe(r, columns[c], bitsPerSample + if (side) 1 else 0)
        }
        r.align()
        val end = r.byte
        check(r.read(16) == crc16(start, end)) { "frame CRC-16 at $start" }
        for (i in 0 until blockSize) {
            when (channelCode) {
                8 -> { out.add(columns[0][i]); out.add(columns[0][i] - columns[1][i]) }
                9 -> { out.add(columns[0][i] + columns[1][i]); out.add(columns[1][i]) }
                10 -> {
                    val side = columns[1][i]
                    val mid = (columns[0][i] shl 1) or (side and 1)
                    out.add((mid + side) shr 1); out.add((mid - side) shr 1)
                }
                else -> for (c in 0 until count) out.add(columns[c][i])
            }
        }
        return r.byte
    }

    private fun subframe(r: Bits, into: IntArray, bps: Int) {
        check(r.read(1) == 0)
        val type = r.read(6)
        check(r.read(1) == 0) { "wasted bits" }
        when {
            type == 0 -> into.fill(r.signed(bps))
            type == 1 -> for (i in into.indices) into[i] = r.signed(bps)
            type in 8..12 -> {
                fixedSubframes++
                val order = type - 8
                for (i in 0 until order) into[i] = r.signed(bps)
                val residual = IntArray(into.size - order)
                residuals(r, residual, into.size, order, bps)
                for (i in order until into.size) {
                    val e = residual[i - order]
                    into[i] = e + when (order) {
                        0 -> 0
                        1 -> into[i - 1]
                        2 -> 2 * into[i - 1] - into[i - 2]
                        3 -> 3 * into[i - 1] - 3 * into[i - 2] + into[i - 3]
                        else -> 4 * into[i - 1] - 6 * into[i - 2] + 4 * into[i - 3] - into[i - 4]
                    }
                }
            }
            type >= 32 -> {
                lpcSubframes++
                val order = type - 32 + 1
                for (i in 0 until order) into[i] = r.signed(bps)
                val precision = r.read(4) + 1
                val shift = r.signed(5)
                val coefficients = IntArray(order) { r.signed(precision) }
                val residual = IntArray(into.size - order)
                residuals(r, residual, into.size, order, bps)
                for (i in order until into.size) {
                    var sum = 0L
                    for (j in 0 until order) sum += coefficients[j].toLong() * into[i - 1 - j]
                    into[i] = residual[i - order] + (sum shr shift).toInt()
                }
            }
            else -> error("subframe type $type")
        }
    }

    private fun residuals(r: Bits, out: IntArray, block: Int, order: Int, bps: Int) {
        val method = r.read(2)
        check(method <= 1)
        val paramBits = if (method == 0) 4 else 5
        val escape = (1 shl paramBits) - 1
        val partitionOrder = r.read(4)
        val partitions = 1 shl partitionOrder
        var at = 0
        for (p in 0 until partitions) {
            val n = (block shr partitionOrder) - if (p == 0) order else 0
            val param = r.read(paramBits)
            if (param == escape) {
                val width = r.read(5)
                repeat(n) { out[at++] = r.signed(width) }
            } else {
                repeat(n) {
                    val q = r.unary()
                    val u = (q shl param) or (if (param == 0) 0 else r.read(param))
                    out[at++] = (u ushr 1) xor -(u and 1)
                }
            }
        }
        check(at == out.size) { "$at residuals, $block - $order expected ($bps bits)" }
    }
}
