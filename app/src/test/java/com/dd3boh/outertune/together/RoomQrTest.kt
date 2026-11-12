/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A quarter turn: a phone held upright hands its camera's frame over sideways. */
    private fun turned(modules: Array<BooleanArray>) =
        Array(modules.size) { y -> BooleanArray(modules.size) { x -> modules[modules.size - 1 - x][y] } }

    /** The code as ZXing draws it left to itself, in the mask of its own choice. */
    private fun zxingOwn(text: String): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
    }

    /**
     * Rooms whose code in ZXing's own mask its reader could not read (19 Oct 2026). It met a spot
     * in the data with a corner square's proportions and stopped looking (the first three, the
     * third in mask 1; H6PQYKQX9MG8 only on its side), or a spot it took hid a real square
     * (34ZCRLMVV6YC upside down).
     */
    private val onceUnreadable = listOf("TL53UDR5E95G", "Y73X736ERB7D", "RKU3KKWT65PW", "H6PQYKQX9MG8", "34ZCRLMVV6YC")

    @Test
    fun `a room's QR code reads back as its invite link`() {
        val codes = onceUnreadable.map { RoomCode.parse(it)!! } + List(20) { RoomCode.generate() }
        for (code in codes) {
            var modules = RoomQr.modules(code.appLink)
            // Whichever way up the other phone holds it.
            for (turn in listOf(0, 90, 180, 270)) {
                val text = try {
                    read(modules)
                } catch (e: ReaderException) {
                    throw AssertionError("$code turned $turn: $e")
                }
                assertEquals(code.appLink, text)
                // And the link it reads opens the same room.
                assertEquals(code, RoomCode.parse(text))
                modules = turned(modules)
            }
        }
    }

    @Test
    fun `a code with a spot the reader could take for a corner square gets another mask, any other keeps ZXing's own`() {
        for (raw in onceUnreadable) {
            val link = RoomCode.parse(raw)!!.appLink
            assertTrue(raw, RoomQr.hasLookAlike(zxingOwn(link)))
            assertFalse(raw, RoomQr.hasLookAlike(RoomQr.modules(link)))
        }
        repeat(20) {
            val link = RoomCode.generate().appLink
            val own = zxingOwn(link)
            if (!RoomQr.hasLookAlike(own)) assertTrue(link, RoomQr.modules(link).contentDeepEquals(own))
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

    @Test
    fun `closing the room is asked first, leaving it as a guest is not`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertTrue(dialog.contains("onClick = { if (room.owner) confirmingClose = true else onLeave() }"))
        assertTrue(dialog.contains("R.string.together_close_room_title"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("together_close_room_title", "together_close_room_text")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
    }
}

