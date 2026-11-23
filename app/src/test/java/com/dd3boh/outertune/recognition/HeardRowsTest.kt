/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What you have heard was a list of plain text, "Billie Jean / Michael Jackson · 20 Nov 2026
 * 18:25", nothing like the song rows of every other list (his ask of 20 Nov 2026: "proper song
 * cards"). Its rows are now those rows, read here from the screen's source.
 */
class HeardRowsTest {
    private val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/RecognitionHistoryScreen.kt").readText()

    private fun body(name: String): String =
        screen.substringAfter("private fun $name(").substringBefore("\n}\n")

    @Test
    fun `a song the library holds is the library's own row`() {
        assertTrue(screen.contains("SongListItem("))
        assertTrue("its time takes the length's place", screen.contains("caption = heardWhen(entry.at)"))
    }

    @Test
    fun `any other song is built from the pieces of every song row`() {
        val row = body("HeardListItem")
        assertTrue(row.contains("ListItem("))
        assertTrue(row.contains("ItemThumbnail("))
        assertTrue("the cover's own size", row.contains("Modifier.size(ListThumbnailSize)"))
        assertTrue("the dots and a long press open one menu", row.contains("onLongClick = onMenu"))
    }

    @Test
    fun `its menu is the one search results open, on YouTube's own song`() {
        assertTrue(screen.contains("viewModel.songOnYouTube(videoId)"))
        assertTrue(screen.contains("YouTubeSongMenu("))
    }

    @Test
    fun `a listen still waiting is in the same shape`() {
        assertTrue(body("WaitingRow").contains("ListItem("))
    }

    @Test
    fun `when it was heard is short, in the History page's words`() {
        assertTrue(screen.contains("format.short("))
        assertFalse("the long date and time are gone", screen.contains("getDateTimeInstance"))
    }

    @Test
    fun `a tap still plays the song, or searches for it`() {
        assertTrue(screen.contains("YouTubeQueue(WatchEndpoint(videoId = id), known)"))
        assertTrue(screen.contains("navController.navigate(\"search/\${query.urlEncode()}\")"))
    }
}
