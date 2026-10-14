/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issue #22: local media off and on again left the Folders tab hidden. */
class LocalMediaFoldersTest {
    @Test
    fun `the Folders tab comes back where it was, the others in their order`() {
        assertEquals(DEFAULT_ENABLED_TABS, withFoldersTab(DEFAULT_ENABLED_TABS.filterNot { it == 'F' }))
        assertEquals("SHFM", withFoldersTab("SHM"))
        assertEquals("HSF", withFoldersTab("HS"))
        // Already there: left alone.
        assertEquals("FHSM", withFoldersTab("FHSM"))
    }

    @Test
    fun `turning local media on gives the tab back`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/LocalPlayerSettings.kt").readText()
        val on = screen.substringAfter("onLocalLibEnableChange(it)").substringBefore("askForMusicOnTurningOn")
        assertTrue("turning local media on does not put the Folders tab back", "onEnabledTabsChange(withFoldersTab(enabledTabs))" in on)
    }
}
