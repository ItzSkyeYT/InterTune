/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sync now pressed while another sync was running (the one at launch, a pull on a Library tab)
 * said "Nothing to sync. Check that sync is on for at least one kind of content." (audit of 2 Nov
 * 2026, 3.4): each kind already being synced answered as one switched off. It says that a sync is
 * already running now.
 */
class SyncBusyTest {
    @Test
    fun `each kind already being synced answers so, not as one switched off`() {
        val source = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()
        for (kind in listOf("LikedSongs", "Songs", "Albums", "Artists", "Playlists")) {
            val guard = source.substringAfter("if (_isSyncingRemote$kind.value) {", "").substringBefore("}")
            assertTrue("$kind: $guard", guard.contains("return SyncResult.BUSY"))
        }
    }

    @Test
    fun `Sync now says a sync is running only when nothing else came of it`() {
        assertEquals(SyncResult.BUSY, SyncResult.combine(listOf(SyncResult.BUSY, SyncResult.BUSY)))
        // A kind switched off beside the ones already under way.
        assertEquals(SyncResult.BUSY, SyncResult.combine(listOf(SyncResult.BUSY, SyncResult.NOTHING)))
        // The other kinds ran, so what it did is said: done, or not finished.
        assertEquals(SyncResult.SYNCED, SyncResult.combine(listOf(SyncResult.BUSY, SyncResult.SYNCED)))
        assertEquals(SyncResult.FAILED, SyncResult.combine(listOf(SyncResult.BUSY, SyncResult.SYNCED, SyncResult.FAILED)))
        assertEquals(SyncResult.NOTHING, SyncResult.combine(listOf(SyncResult.NOTHING)))
    }

    @Test
    fun `and Sync now says it`() {
        val page = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/SyncFrag.kt").readText()
        assertTrue(page.contains("SyncResult.BUSY -> R.string.sync_progress_busy"))
    }

    @Test
    fun `its words are written`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        assertEquals(1, Regex("<string name=\"sync_progress_busy\"").findAll(english).count())
    }
}
