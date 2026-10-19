/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scan button of Listen together (his ask of 17 Oct 2026): this phone's camera reads the QR
 * code on the other phone and joins its room.
 */
class RoomScanTest {

    private val width = 640
    private val height = 480
    /** Wider than a row, as a camera's rows often are. */
    private val rowStride = 704

    /** A camera frame's brightness: [modules] with their quiet zone in the middle of a grey frame, or the frame alone. */
    private fun frame(modules: Array<BooleanArray>?, scale: Int = 8): ByteArray {
        val bytes = ByteArray(rowStride * height) { 0x80.toByte() }
        if (modules == null) return bytes
        val n = modules.size
        val side = (n + 8) * scale
        val left = (width - side) / 2
        val top = (height - side) / 2
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / scale - 4
            val my = y / scale - 4
            val dark = mx in 0 until n && my in 0 until n && modules[my][mx]
            bytes[(top + y) * rowStride + left + x] = if (dark) 0x10 else 0xF0.toByte()
        }
        return bytes
    }

    /** Rooms whose code in ZXing's own mask its reader could not read in this frame (19 Oct 2026, see RoomQr.modules). */
    private val onceUnreadable = listOf("MNCX4QZNEXSS", "KUTYVUNFXNMC", "2UUJ4QBCMXXU")

    /** A quarter turn: a phone held upright hands its camera's frame over sideways. */
    private fun turned(modules: Array<BooleanArray>) =
        Array(modules.size) { y -> BooleanArray(modules.size) { x -> modules[modules.size - 1 - x][y] } }

    @Test
    fun `a room's QR code seen by the camera reads back as the room`() {
        val codes = onceUnreadable.map { RoomCode.parse(it)!! } + List(10) { RoomCode.generate() }
        for (code in codes) {
            var modules = RoomQr.modules(code.appLink)
            for (turn in listOf(0, 90, 180, 270)) {
                val seen = RoomScan.read(frame(modules), rowStride, width, height)
                assertEquals("$code turned $turn", code.appLink, seen)
                assertEquals(code, RoomScan.room(seen))
                modules = turned(modules)
            }
        }
    }

    @Test
    fun `a frame with no code in it, or a code that is not a room's, is no room`() {
        assertNull(RoomScan.read(frame(null), rowStride, width, height))
        val other = RoomScan.read(frame(RoomQr.modules("https://example.org/not-a-room")), rowStride, width, height)
        assertEquals("https://example.org/not-a-room", other)
        assertNull(RoomScan.room(other))
    }

    @Test
    fun `the start page offers the scan, the camera is asked for only then, and a phone without one still installs`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertTrue(sheet.substringAfter("private fun StartPage(").substringBefore("private fun RoomPage(").contains("RoomScanDialog("))
        val scanner = File("src/main/java/com/dd3boh/outertune/ui/dialog/RoomScanDialog.kt").readText()
        assertTrue(scanner.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(scanner.contains("Manifest.permission.CAMERA"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(Regex("""android\.hardware\.camera\.any"\s+android:required="false"""").containsMatchIn(manifest))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("together_scan", "together_scan_hint", "together_scan_refused")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
    }
}
