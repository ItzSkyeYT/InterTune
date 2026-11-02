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
 * Refetch in the lyrics menu deleted the stored lyrics first, lyrics picked by hand included, and
 * then asked the sources (audit of 2 Nov 2026, 3.13). Offline, or with no source finding any, the
 * view was handed nothing, which it draws as the loading placeholder, until the song changed; past
 * the minute of the lookup's limit it was never answered at all, and the lyrics were gone either
 * way. Now what is stored stays until other lyrics come back, and the view keeps what it shows.
 */
class RefetchKeepsLyricsTest {
    private val src = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `Refetch no longer deletes the stored lyrics before asking`() {
        val viewModel = File(src, "viewmodels/LyricsMenuViewModel.kt").readText()
        val refetch = viewModel.substringAfter("fun refetchLyrics(")
        assertFalse(refetch, refetch.contains("deleteLyricById"))
        assertTrue("the lookup of its own", refetch.contains("lyricsHelper.refetch("))
    }

    @Test
    fun `the lookup writes over what is stored only with lyrics in hand`() {
        val helper = File(src, "lyrics/LyricsHelper.kt").readText()
        val refetch = helper.substringAfter("suspend fun refetch(").substringBefore("\n    }\n")
        assertFalse(refetch, refetch.contains("delete"))
        assertFalse("never records the song as having none", refetch.contains("LYRICS_NOT_FOUND"))
        val asked = refetch.indexOf("getRemoteLyrics(")
        val written = refetch.indexOf("upsert(")
        assertTrue(refetch, asked > 0 && written > asked)
    }

    @Test
    fun `the view is never handed nothing, so the placeholder never stays`() {
        val menu = File(src, "ui/menu/LyricsMenu.kt").readText()
        val call = menu.substringAfter("viewModel.refetchLyrics(song) {").substringBefore("GridMenuItem(")
        assertTrue(call, call.contains("is LyricsHelper.Refetched.Found -> {"))
        // Only found lyrics reach the view; nothing else is handed on.
        assertTrue(call, Regex("onRefreshRequest\\(([^)]*)\\)").findAll(call).all { it.groupValues[1] == "result.lyrics" })
    }
}
