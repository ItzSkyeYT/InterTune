/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lyrics timing is offered only over the playing song's own lyrics. While the next song's lyrics
 * were looked up, the last song's lines stayed on screen, and a move made against them was saved
 * for the new song and applied to its own lyrics once they came. This reads the source, as
 * LyricsOpeningTest does.
 */
class LyricsTimingOwnLinesTest {

    @Test
    fun `the lyrics on screen say which song they are of`() {
        val connection = File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText()
        assertTrue(Regex("""val currentLyrics: Flow<Pair<String, SemanticLyrics>> = LyricsLookup\.bySong\(mediaMetadata\) \{ \w+ ->\s*\w+\.id to """).containsMatchIn(connection))
        val lyrics = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        assertTrue(lyrics.contains("lyricsSong = playerLyrics?.first"))
    }

    @Test
    fun `timing is offered only when they are the playing song's`() {
        val lyrics = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        assertTrue(lyrics.contains("onAdjustTiming = if (isSynced && lyricsSong == mediaMetadata.id)"))
    }
}
