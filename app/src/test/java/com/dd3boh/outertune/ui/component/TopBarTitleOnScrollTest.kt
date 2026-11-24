/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His recording of a playlist (24 Nov 2026, "the fact that the title appears immediately is quite annoying"): the name
 * came into the top bar the moment the header passed under the bar, which floats over the list, so it landed at once on
 * Play and Shuffle, still to be seen under it, and went as soon as the list moved back. Now the name waits until the
 * header has left the screen, and fades in and out.
 */
class TopBarTitleOnScrollTest {
    private val component = File("src/main/java/com/dd3boh/outertune/ui/component/FloatingTopBar.kt").readText()
    private val screens = listOf("LocalPlaylistScreen", "AutoPlaylistScreen", "OnlinePlaylistScreen")
        .associateWith { File("src/main/java/com/dd3boh/outertune/ui/screens/playlist/$it.kt").readText() }

    @Test
    fun `the name waits until the header has left the screen, the part under the bar included`() {
        val gone = component.substringAfter("fun rememberHeaderGone(").substringBefore("\n}\n")
        // The first item still laid out, in the list's padding under the bar too, not the first one past that padding.
        assertTrue(gone, "state.layoutInfo.visibleItemsInfo.firstOrNull()?.index" in gone)
        assertFalse(gone, "firstVisibleItemIndex" in gone)
        for ((name, screen) in screens) {
            assertTrue(name, "val showTopBarTitle by rememberHeaderGone(lazyListState)" in screen)
            assertFalse(name, "lazyListState.firstVisibleItemIndex > 0" in screen)
        }
    }

    @Test
    fun `the name fades in and out of the bar, and is not shown beside the header in two panes`() {
        val onScroll = component.substringAfter("fun TopBarTitleOnScroll(").substringBefore("\n}\n")
        assertTrue(onScroll, "AnimatedVisibility(visible = shown" in onScroll)
        assertTrue(onScroll, "enter = fadeIn(" in onScroll && "exit = fadeOut(" in onScroll)
        for ((name, screen) in screens) {
            val title = screen.substringAfter("} else if (!twoPanes) {").substringBefore("\n                }")
            assertTrue("$name: $title", "TopBarTitleOnScroll(" in title && "showTopBarTitle)" in title)
            // No name popping in anywhere else on these pages.
            assertFalse(name, "TopBarTitle(" in screen.replace("TopBarTitleOnScroll(", ""))
        }
    }
}
