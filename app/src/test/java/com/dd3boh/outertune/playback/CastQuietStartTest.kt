/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A cast starts at a quarter of the receiver's volume at most, never louder than it was. */
class CastQuietStartTest {
    @Test
    fun `a cast starts quiet`() {
        assertEquals(25, QUIET_START)
        val session = File("src/main/java/com/dd3boh/outertune/playback/CastSession.kt").readText()
        assertTrue(session.contains("if (link.caps.hasVolume && remote.deviceVolume > QUIET_START) {"))
        assertTrue(session.contains("remote.setDeviceVolume(QUIET_START, 0)"))
    }
}
