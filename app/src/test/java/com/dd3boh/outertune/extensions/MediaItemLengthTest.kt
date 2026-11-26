/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 26 Nov 2026 (the whole-app scan): a long MP3 with no Xing or Info header showed no length and no
 * seek bar in Android's own controls until its last minutes, as the player learns its length only
 * near the end. Every song's item now carries the length the library knows, which media3's session
 * takes when the player has none (LegacyConversions.convertToMediaMetadataCompat).
 */
class MediaItemLengthTest {

    @Test
    fun `a known length goes in milliseconds, an unknown one not at all`() {
        assertEquals(180_000L, knownLengthMs(180))
        assertEquals(3_600_000L, knownLengthMs(3600))
        assertNull("the library's -1", knownLengthMs(-1))
        assertNull(knownLengthMs(0))
        assertNull(knownLengthMs(null))
    }

    @Test
    fun `every way a song becomes an item gives its length`() {
        val code = File("src/main/java/com/dd3boh/outertune/extensions/MediaItemExt.kt").readText()
        assertEquals("one for a stored song, a YouTube result and a queue entry", 3, Regex("""\.setDurationMs\(knownLengthMs\(""").findAll(code).count())
        assertTrue(".setDurationMs(knownLengthMs(song.duration))" in code)
    }
}
