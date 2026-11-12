/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A lyrics video took a minute of the song at most, what a story holds; it takes up to ten minutes
 * now (1 Nov 2026). Making one takes about as long as it lasts on a slower phone, so the screen
 * stays on while it is made, and the note shown for picks that are too long says the real limit.
 */
class LyricsVideoLengthTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the screen stays on while a video is made, through the shared holder`() {
        val card = source("ui/component/LyricsCard.kt")
        assertTrue(card.contains("KeepScreenOnController.set(view, KeepScreenOnReason.LYRICS_VIDEO, making != null)"))
        assertTrue("and let go with the dialog", card.contains("onDispose { KeepScreenOnController.set(view, KeepScreenOnReason.LYRICS_VIDEO, false) }"))
        assertFalse("never the flag itself, which the player's lyrics hold too", card.contains("keepScreenOn ="))
    }

    @Test
    fun `the note for picks that are too long says how many minutes a video takes`() {
        val lyrics = source("ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("R.plurals.lyrics_pick_most_video_minutes, LyricsShare.MOST_LINES, LyricsShare.MOST_LINES, (LyricsVideoPlan.LONGEST_MS / 60_000).toInt()"))
        assertFalse("not the old one that says a minute", lyrics.contains("R.plurals.lyrics_pick_most_video,"))
        for (file in listOf("values", "values-fr")) {
            val texts = File("src/main/res/$file/strings-ot.xml").readText()
            val plural = Regex("""<plurals name="lyrics_pick_most_video_minutes"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(texts)
            assertTrue("$file has the note", plural != null)
            for (item in Regex("""<item quantity="[a-z]+">([^<]*)</item>""").findAll(plural!!.groupValues[1])) {
                assertTrue("$file: ${item.groupValues[1]}", item.groupValues[1].contains("%1\$d") && item.groupValues[1].contains("%2\$d minutes"))
            }
        }
    }
}
