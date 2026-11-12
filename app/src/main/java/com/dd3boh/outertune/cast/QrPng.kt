/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.io.encoding.Base64

/**
 * A QR code's modules as a PNG a TV's page can draw: black on white, one bit a pixel, with the
 * white margin a scanner needs. Written here, with no image library, so it runs (and is tested)
 * on a plain JVM as it does on the phone.
 */
object QrPng {
    /** The margin a QR code asks for, in modules. */
    const val QUIET_MODULES = 4

    /**
     * The PNG of [modules] (row by row, true for dark), each module [scale] pixels square, with
     * [quiet] white modules all round it.
     */
    fun encode(modules: Array<BooleanArray>, scale: Int = 8, quiet: Int = QUIET_MODULES): ByteArray {
        require(modules.isNotEmpty() && modules.all { it.size == modules.size }) { "A QR code is a square" }
        require(scale >= 1 && quiet >= 0) { "A scale of $scale and a margin of $quiet" }
        val side = (modules.size + 2 * quiet) * scale
        val rowBytes = (side + 7) / 8
        // Each row is a filter byte (0: none) and the pixels, a set bit for white.
        val raw = ByteArray((rowBytes + 1) * side)
        for (y in 0 until side) {
            val row = y * (rowBytes + 1)
            val moduleY = y / scale - quiet
            for (byte in 0 until rowBytes) raw[row + 1 + byte] = 0xFF.toByte()
            if (moduleY !in modules.indices) continue
            for (x in 0 until side) {
                val moduleX = x / scale - quiet
                if (moduleX in modules.indices && modules[moduleY][moduleX]) {
                    val at = row + 1 + x / 8
                    raw[at] = (raw[at].toInt() and (0x80 shr (x % 8)).inv()).toByte()
                }
            }
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        chunk(out, "IHDR", header(side))
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** The same PNG as a `data:image/png;base64,` url, which a page draws as an image with no request. */
    fun dataUrl(modules: Array<BooleanArray>, scale: Int = 8, quiet: Int = QUIET_MODULES): String =
        "data:image/png;base64," + Base64.encode(encode(modules, scale, quiet))

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun header(side: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int(side))
        out.write(int(side))
        out.write(1) // bit depth
        out.write(0) // grayscale
        out.write(0) // deflate
        out.write(0) // adaptive filtering
        out.write(0) // no interlace
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(int(data.size))
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(body)
        out.write(int(CRC32().also { it.update(body) }.value))
    }

    private fun int(value: Int): ByteArray = int(value.toLong())

    private fun int(value: Long): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
}
