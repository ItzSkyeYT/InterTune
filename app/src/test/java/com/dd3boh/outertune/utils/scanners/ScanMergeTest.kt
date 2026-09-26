/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScanMergeTest {

    private val added = LocalDateTime.of(2025, 3, 1, 10, 0)
    private val likedOn = LocalDateTime.of(2025, 6, 2, 21, 30)
    private val scanTime = LocalDateTime.of(2026, 9, 26, 9, 0)

    /** The row as the library has it: liked, added in March, file in Music. */
    private val stored = SongEntity(
        id = "LSstored1",
        title = "Old title",
        duration = 200,
        thumbnailUrl = "/storage/emulated/0/Music/a.flac",
        inLibrary = added,
        isLocal = true,
        localPath = "/storage/emulated/0/Music/a.flac",
        liked = true,
        likedDate = likedOn,
        albumId = "LAold",
        albumName = "Old album",
        year = 1999,
    )

    /** What TagLibScanner hands back for the same file: a fresh id, no like, added now. */
    private val scanned = SongEntity(
        id = "LSscanned",
        title = "New title",
        duration = 201,
        thumbnailUrl = "/storage/emulated/0/Music/a.flac",
        inLibrary = scanTime,
        isLocal = true,
        localPath = "/storage/emulated/0/Music/a.flac",
        albumId = "LAnew",
        albumName = "New album",
        year = 2000,
        trackNumber = 3,
    )

    @Test
    fun `a rescan keeps the like and the date added`() {
        val merged = ScanMerge.intoExisting(scanned, stored)
        assertEquals("LSstored1", merged.id)
        assertTrue(merged.liked)
        assertEquals(likedOn, merged.likedDate)
        assertEquals(added, merged.inLibrary)
    }

    @Test
    fun `a rescan takes everything about the file from the scan`() {
        val merged = ScanMerge.intoExisting(scanned, stored)
        assertEquals("New title", merged.title)
        assertEquals(201, merged.duration)
        assertEquals("New album", merged.albumName)
        assertEquals(2000, merged.year)
        assertEquals(3, merged.trackNumber)
        assertEquals(scanned.localPath, merged.localPath)
    }

    @Test
    fun `a moved file keeps its date added and takes the new path`() {
        val moved = scanned.copy(localPath = "/storage/emulated/0/Music/Moved/a.flac")
        val merged = ScanMerge.intoExisting(moved, stored)
        assertEquals("/storage/emulated/0/Music/Moved/a.flac", merged.localPath)
        assertEquals(added, merged.inLibrary)
    }

    @Test
    fun `a disabled song comes back liked, dated from the scan`() {
        // Disabling clears inLibrary and nothing else, so the like is still on the row.
        val disabled = stored.copy(inLibrary = null)
        val merged = ScanMerge.intoExisting(scanned, disabled)
        assertTrue(merged.liked)
        assertEquals(likedOn, merged.likedDate)
        assertEquals(scanTime, merged.inLibrary)
    }

    @Test
    fun `a song never liked stays unliked`() {
        val merged = ScanMerge.intoExisting(scanned, stored.copy(liked = false, likedDate = null))
        assertFalse(merged.liked)
        assertEquals(null, merged.likedDate)
    }

    @Test
    fun `dateDownload is carried over rather than cleared`() {
        val downloaded = LocalDateTime.of(2025, 1, 1, 0, 0)
        val merged = ScanMerge.intoExisting(scanned, stored.copy(dateDownload = downloaded))
        assertEquals(downloaded, merged.dateDownload)
    }

}
