/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saved albums keep YouTube Music's order (27 Oct 2026, found by reading a fork's fix): the sync
 * marked each album in a parallel job stamped with the moment it ran, so a first sync gave the
 * album library a random "date added" order.
 */
class AlbumSyncOrderTest {
    private val albums = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()
        .substringAfter("private suspend fun albums(").substringBefore("    private suspend fun ")

    @Test
    fun `albums are marked one after another, each a millisecond after the one before`() {
        assertTrue(albums.contains("remoteAlbums.forEachIndexed { index, remoteAlbum ->"))
        assertTrue(albums.contains("val stamp = firstStamp.plusNanos(index * 1_000_000L)"))
        assertTrue(albums.contains("copy(bookmarkedAt = stamp)"))
    }

    @Test
    fun `nothing marks albums in parallel any more`() {
        val marking = albums.substringAfter("// Add or mark albums in local database").substringBefore("result = if (remote.complete)")
        assertFalse(marking.contains("launch(Dispatchers.IO)"))
        assertFalse(marking.contains("localToggleLike()"))
    }
}
