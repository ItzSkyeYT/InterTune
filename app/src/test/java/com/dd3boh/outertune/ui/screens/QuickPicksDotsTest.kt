/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quick picks from YouTube had no dots on their rows (8 Nov 2026: "Why are there no three dots"),
 * while the library's rows in the same grid had them. Both open the song's menu the same way now.
 */
class QuickPicksDotsTest {

    @Test
    fun `YouTube's quick picks have the dots, opening the menu a long press opens`() {
        val home = File("src/main/java/com/dd3boh/outertune/ui/screens/HomeScreen.kt").readText()
        val yt = home.substringAfter("items = ytPicks,").substringBefore("items = localPicks,")
        assertTrue("the dots", "Icons.Rounded.MoreVert" in yt && "contentDescription = stringResource(R.string.options)" in yt)
        assertTrue("the dots open the menu", "openMenu()" in yt.substringAfter("IconButton(").substringBefore("Icons.Rounded.MoreVert"))
        assertTrue("a long press still opens it", "openMenu()" in yt.substringAfter("onLongClick = {"))
        assertEquals("one menu for both", 1, Regex("""YouTubeSongMenu\(""").findAll(yt).count())
    }
}
