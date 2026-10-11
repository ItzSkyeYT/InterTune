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

/** What Android Auto is handed since the rework, where a mistake would show only in a car. */
class AutoBrowseTest {

    private fun source(name: String) = File("src/main/java/com/dd3boh/outertune/playback/$name").readText()

    @Test
    fun `three tabs, so that the car's own More is the fourth`() {
        // The car puts the settings in a tab of its own, "More". With four of ours the last one went in there too.
        assertEquals(listOf(AutoBrowse.HOME, MusicService.PLAYLIST, AutoBrowse.LIBRARY), AutoBrowse.TABS)
    }

    @Test
    fun `a list can be subscribed to`() {
        // Media3's own onSubscribe asks onGetItem for the list, which knows songs only: the car
        // was refused and never heard of a change. Read from the source: a JVM test cannot run it.
        val callback = source("MediaLibrarySessionCallback.kt")
        assertTrue("MediaLibrarySessionCallback no longer answers onSubscribe itself", "override fun onSubscribe(" in callback)
    }

    @Test
    fun `every tab is told when the song changes`() {
        // The car keeps the tabs it was handed and never asks again: unsaid, the row of what is
        // playing would show the song of the moment the car was plugged in.
        val service = source("MusicService.kt")
        val transition = service.substringAfter("override fun onMediaItemTransition(").substringBefore("\n    }\n")
        assertTrue(
            "onMediaItemTransition no longer tells the car's tabs",
            "AutoBrowse.TABS.forEach { mediaSession.notifyChildrenChanged(" in transition,
        )
    }
}
