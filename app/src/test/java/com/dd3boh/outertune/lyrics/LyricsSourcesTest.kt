/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order the lyrics sources are asked in (Unreleased.LYRICS_SOURCES). */
class LyricsSourcesTest {

    @Test
    fun `nothing set is the order they were always asked in`() {
        val always = listOf("youtube_captions", "lrclib", "kugou", "youtube_music")
        assertEquals(always, LyricsSources.order(null))
        assertEquals(always, LyricsSources.order(""))
    }

    @Test
    fun `a set order is kept`() {
        val mine = listOf("lrclib", "kugou", "youtube_music", "youtube_captions")
        assertEquals(mine, LyricsSources.order(LyricsSources.encode(mine)))
    }

    @Test
    fun `a source the setting does not name takes its default place at the end`() {
        // A store from a version with fewer sources, or one written by hand.
        assertEquals(listOf("kugou", "lrclib", "youtube_captions", "youtube_music"), LyricsSources.order("kugou,lrclib"))
    }

    @Test
    fun `unknown and repeated ids are left out`() {
        assertEquals(listOf("lrclib", "youtube_captions", "kugou", "youtube_music"), LyricsSources.order("musixmatch, lrclib,lrclib ,"))
    }

    @Test
    fun `the lookup and the lyrics search both ask in the set order`() {
        val helper = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsHelper.kt").readText()
        assertTrue("LyricsSources.order(context.dataStore.get(LyricsSourceOrderKey, \"\"))" in helper)
        assertTrue("LyricsLookup.firstFound(lyricsProviders(), query)" in helper)
        assertTrue("lyricsProviders().forEach { provider ->" in helper)
        assertFalse("no fixed list left", "listOf(YouTubeSubtitleLyricsProvider, LrcLibLyricsProvider" in helper)
    }
}
