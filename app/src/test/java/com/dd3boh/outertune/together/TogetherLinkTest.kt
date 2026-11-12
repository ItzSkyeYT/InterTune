/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A room's invite link opens the app, which asks before joining. */
class TogetherLinkTest {
    @Test
    fun `intertune together links reach MainActivity, which asks before joining`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // The app's own scheme on MainActivity takes intertune://together/<code> with every other link of its own.
        assertTrue("<data android:scheme=\"intertune\" />" in manifest)
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("TogetherInviteDialog(code, playerConnection," in main)
        assertTrue("link.startsWith(\"intertune://together/\", ignoreCase = true)" in main)
    }

    @Test
    fun `the link carries the code`() {
        val code = RoomCode.generate()
        assertEquals("intertune://together/${code.raw}", code.appLink)
        assertEquals(code, RoomCode.parse(code.appLink))
    }
}
