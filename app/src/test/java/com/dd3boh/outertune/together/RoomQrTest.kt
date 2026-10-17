/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A room's QR code (his yes of 17 Oct 2026): one phone shows it, the other's camera reads it and
 * opens the invite. It holds the same link Share sends, so joining by it is the path already
 * tested.
 */
class RoomQrTest {

    /** [modules] drawn as a camera would see them: a white quiet zone of four, each module [scale] pixels. */
    private fun read(modules: Array<BooleanArray>, scale: Int = 4): String {
        val n = modules.size + 8
        val side = n * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale - 4
            val y = i / side / scale - 4
            val dark = x in modules.indices && y in modules.indices && modules[y][x]
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun `a room's QR code reads back as its invite link`() {
        repeat(20) {
            val code = RoomCode.generate()
            val modules = RoomQr.modules(code.appLink)
            assertEquals(code.appLink, read(modules))
            // And the link it reads opens the same room.
            assertEquals(code, RoomCode.parse(read(modules)))
        }
    }

    @Test
    fun `the code is square, and small enough to read from across a table`() {
        val modules = RoomQr.modules(RoomCode.generate().appLink)
        assertTrue(modules.all { it.size == modules.size })
        // Version 3 is 29 modules: a link of this length fits well within it at level M.
        assertTrue("${modules.size} modules", modules.size <= 33)
    }

    @Test
    fun `the room's sheet offers it beside Copy and Share, as bright as the screen goes`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertTrue(dialog.contains("RoomQr.modules(code.appLink)"))
        assertTrue(dialog.contains("BRIGHTNESS_OVERRIDE_FULL"))
        assertTrue("a QR code can be left without a trap: it scrolls", dialog.substringAfter("private fun RoomQrDialog(").contains("verticalScroll"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("together_qr", "together_qr_title", "together_qr_hint")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
    }
}
