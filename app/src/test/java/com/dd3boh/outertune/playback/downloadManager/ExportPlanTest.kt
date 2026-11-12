/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.playback.downloadManager.ExportSources.Source
import com.dd3boh.outertune.playback.downloadManager.ExportSummary.Part
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Export to folder says of itself and where it looks for a song (Unreleased.DOWNLOAD_FORMATS). No Android in here. */
class ExportPlanTest {

    private fun item(n: Int, local: Boolean = false) = ExportItem("id$n", "Song $n", local)
    private fun skip(n: Int, reason: SkipReason) = ExportSkip(item(n), reason)

    @Test
    fun `a song is looked for in a folder, then the download cache, then the song cache, and never fetched`() {
        assertEquals(Source.FOLDER_FILE, ExportSources.pick(false, inFolder = true, inDownloadCache = true, inSongCache = true))
        assertEquals(Source.DOWNLOAD_CACHE, ExportSources.pick(false, inFolder = false, inDownloadCache = true, inSongCache = true))
        assertEquals(Source.SONG_CACHE, ExportSources.pick(false, inFolder = false, inDownloadCache = false, inSongCache = true))
        assertEquals(Source.NOWHERE, ExportSources.pick(false, inFolder = false, inDownloadCache = false, inSongCache = false))
    }

    @Test
    fun `a file the library scanned is a file already, whatever else is known of the song`() {
        assertEquals(Source.LOCAL_FILE, ExportSources.pick(true, inFolder = true, inDownloadCache = true, inSongCache = true))
        assertEquals(Source.LOCAL_FILE, ExportSources.pick(true, inFolder = false, inDownloadCache = false, inSongCache = false))
    }

    @Test
    fun `the line says how many were exported and how many were skipped, and why`() {
        val result = ExportResult(12, listOf(skip(1, SkipReason.NOT_ON_PHONE)))
        assertEquals(listOf(Part.EXPORTED to 12, Part.NOT_ON_PHONE to 1), ExportSummary.parts(result))
    }

    @Test
    fun `none exported is said, and a reason nobody was skipped for is not`() {
        assertEquals(listOf(Part.EXPORTED to 0), ExportSummary.parts(ExportResult(0, emptyList())))
        val result = ExportResult(0, listOf(skip(1, SkipReason.NOT_ON_PHONE), skip(2, SkipReason.NOT_ON_PHONE), skip(3, SkipReason.NOT_ON_PHONE)))
        assertEquals(listOf(Part.EXPORTED to 0, Part.NOT_ON_PHONE to 3), ExportSummary.parts(result))
    }

    @Test
    fun `every reason is said in its place, after the exported ones`() {
        val result = ExportResult(
            2,
            listOf(skip(1, SkipReason.FAILED), skip(2, SkipReason.LOCAL), skip(3, SkipReason.ALREADY_THERE), skip(4, SkipReason.NOT_ON_PHONE), skip(5, SkipReason.NOT_ON_PHONE)),
        )
        assertEquals(
            listOf(Part.EXPORTED to 2, Part.NOT_ON_PHONE to 2, Part.ALREADY_THERE to 1, Part.LOCAL to 1, Part.FAILED to 1),
            ExportSummary.parts(result),
        )
    }

    @Test
    fun `the songs left out are named, a few of them, and the rest counted`() {
        val result = ExportResult(0, (1..7).map { skip(it, SkipReason.NOT_ON_PHONE) } + skip(8, SkipReason.FAILED))
        assertEquals(listOf("Song 1", "Song 2", "Song 3", "Song 4") to 3, ExportSummary.named(result, SkipReason.NOT_ON_PHONE))
        assertEquals(listOf("Song 8") to 0, ExportSummary.named(result, SkipReason.FAILED))
        assertEquals(emptyList<String>() to 0, ExportSummary.named(result, SkipReason.LOCAL))
        assertEquals(listOf("Song 1", "Song 2") to 5, ExportSummary.named(result, SkipReason.NOT_ON_PHONE, limit = 2))
    }

    @Test
    fun `a result knows whether it was stopped`() {
        assertEquals(false, ExportResult(1, emptyList()).stopped)
        assertEquals(true, ExportResult(1, emptyList(), stopped = true).stopped)
    }
}
