/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A room's invite as a QR code, for a phone beside this one to read with its camera (his yes of
 * 17 Oct 2026). It holds [RoomCode.appLink], the link Share sends, so the camera opens the same
 * "join this room?" question a shared link does.
 */
object RoomQr {
    /**
     * The modules of the QR code of [text], row by row, true for dark. Without the quiet zone:
     * whoever draws it puts white round it. Level M, which leaves room for a scuffed screen and
     * keeps a room's link in a small, coarse code.
     */
    fun modules(text: String): Array<BooleanArray> {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
    }
}
