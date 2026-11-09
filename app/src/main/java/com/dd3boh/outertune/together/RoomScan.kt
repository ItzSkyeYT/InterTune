/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reading a room's QR code off a camera frame (RoomScanDialog), with the decoder of the same
 * library that draws it (RoomQr).
 */
object RoomScan {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * The text of a QR code in a frame's brightness ([luma], the camera's first plane, rows
     * [rowStride] bytes apart), or null when the frame holds none it can read.
     */
    fun read(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: ReaderException) {
        null
    }

    /** The room in what a code reads, its link or its code alone, or null for any other code. */
    fun room(text: String?): RoomCode? = text?.trim()?.let { RoomCode.parse(it) }
}
