/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV files in and out for the parts bench: 16-bit or 32-bit float PCM in, mono or stereo;
 * 32-bit float stereo out, so a part keeps every bit the model gave it (a drum part can peak over
 * full scale) and can be held against the PC's reference sample for sample.
 */
object Wav {
    class Stereo(val rate: Int, val left: FloatArray, val right: FloatArray) {
        val frames: Int get() = left.size
    }

    fun read(file: File): Stereo {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.remaining() >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "$file is not a WAV file" }
        var at = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        while (at + 8 <= bytes.limit()) {
            val id = tag(bytes, at)
            val size = bytes.getInt(at + 4)
            val body = at + 8
            when (id) {
                "fmt " -> {
                    format = bytes.getShort(body).toInt() and 0xffff
                    channels = bytes.getShort(body + 2).toInt()
                    rate = bytes.getInt(body + 4)
                    bits = bytes.getShort(body + 14).toInt()
                    // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the sub-format GUID.
                    if (format == 0xfffe && size >= 26) format = bytes.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> {
                    require(format == 1 && bits == 16 || format == 3 && bits == 32) { "$file: only 16-bit or float PCM (format $format, $bits bits)" }
                    require(channels == 1 || channels == 2) { "$file: $channels channels" }
                    val length = minOf(size, bytes.limit() - body)
                    val frames = length / (bits / 8 * channels)
                    val left = FloatArray(frames)
                    val right = FloatArray(frames)
                    for (f in 0 until frames) {
                        for (c in 0 until channels) {
                            val p = body + (f * channels + c) * (bits / 8)
                            val v = if (bits == 16) bytes.getShort(p) / 32768f else bytes.getFloat(p)
                            if (c == 0) left[f] = v else right[f] = v
                        }
                        if (channels == 1) right[f] = left[f]
                    }
                    return Stereo(rate, left, right)
                }
            }
            at = body + size + (size and 1)
        }
        error("$file has no data")
    }

    /** Writes [length] frames of [left] and [right] from [from] as a 32-bit float stereo WAV. */
    fun writeFloat(file: File, rate: Int, left: FloatArray, right: FloatArray, from: Int = 0, length: Int = left.size - from) {
        val data = length * 8
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(2).putInt(rate).putInt(rate * 8).putShort(8).putShort(32)
        out.put("data".toByteArray()).putInt(data)
        for (f in from until from + length) out.putFloat(left[f]).putFloat(right[f])
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(out.array()) }
    }

    private fun tag(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
}
