/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #105 (0.12.9, a Xiaomi on Android 16): adding songs to a new playlist from its quick search closed
 * the app with "Key add_<id> was already used": the search had listed one song twice, and the rows
 * are keyed by song.
 */
class AddSongsOnceTest {

    @Test
    fun `the quick add list holds each song once`() {
        val model = File("src/main/java/com/dd3boh/outertune/viewmodels/LocalPlaylistViewModel.kt").readText()
        val assigned = Regex("""_addResults\.value = (.+)""").findAll(model).map { it.groupValues[1] }.toList()
        val fromSearch = assigned.filter { "result" in it }
        assertTrue(fromSearch.toString(), fromSearch.isNotEmpty() && fromSearch.all { "distinctBy { it.id }" in it })
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/playlist/LocalPlaylistScreen.kt").readText()
        assertTrue("its rows are keyed by song", "items(items = addResults, key = { \"add_\" + it.id })" in screen)
    }
}
