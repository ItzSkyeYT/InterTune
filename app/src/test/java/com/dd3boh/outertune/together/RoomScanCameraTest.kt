/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The QR scanner binds the camera once CameraX has started, a few hundred milliseconds after the first Scan. Closed
 * before that, the dialog had nothing to unbind, and the camera was bound a moment later all the same, to the screen
 * under the dialog: on whenever the app was in front, privacy dot lit, until that screen ended (review of 0.13,
 * 18 Nov 2026). Now the camera is never bound once the dialog is gone. Read from the source: CameraX and Compose do not
 * run in a unit test.
 */
class RoomScanCameraTest {

    private val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/RoomScanDialog.kt").readText()
    private val camera = dialog.substringAfter("private fun RoomCamera(")

    @Test
    fun `the camera is never bound once the dialog has gone`() {
        val listener = camera.substringAfter("providerFuture.addListener({").substringBefore("}, main)")
        val check = listener.indexOf("if (disposed) return@addListener")
        assertTrue("the listener does not look whether the dialog is gone", check >= 0)
        assertTrue("it looks only after taking the camera", check < listener.indexOf("providerFuture.get()"))
        assertTrue(check < listener.indexOf("bindToLifecycle("))
        // the listener runs on the main thread, where the dialog's end is told: a plain flag is enough
        assertTrue("}, main)" in camera)
        val end = camera.substringAfter("onDispose {").substringBefore("}")
        assertTrue("disposed = true" in end)
        assertTrue(end.indexOf("disposed = true") < end.indexOf("provider?.unbindAll()"))
        assertTrue("var disposed = false" in camera.substringBefore("providerFuture.addListener({"))
    }
}
