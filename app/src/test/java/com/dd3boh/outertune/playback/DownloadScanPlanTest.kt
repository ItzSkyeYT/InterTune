/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full scan of the download folders ends a song's download only when its file is known to be
 * gone (DownloadScanPlan). It used to clear every download, then record again only what it could
 * list and give a path: dvd's 397 liked songs were all forgotten, fetched again, and each got a
 * second file, "Title [id].mka (1)" (20 Nov 2026).
 */
class DownloadScanPlanTest {
    private val dir = "/storage/emulated/0/Music/InterTune"
    private fun p(name: String) = "$dir/$name"

    @Test
    fun `a song listed with the file it is recorded with stays as it is, its date with it`() {
        val plan = DownloadScanPlan.of(
            recorded = mapOf("a" to p("A [a].mka")),
            listed = mapOf("a" to p("A [a].mka")),
            inside = emptySet(), spared = emptySet(), checked = emptyMap(),
        )
        assertEquals(emptyMap<String, String?>(), plan.register)
        assertEquals(emptySet<String>(), plan.remove)
    }

    @Test
    fun `a file new to the database is recorded with its path`() {
        val plan = DownloadScanPlan.of(
            recorded = emptyMap(),
            listed = mapOf("a" to p("A [a].mka")),
            inside = emptySet(), spared = emptySet(), checked = emptyMap(),
        )
        assertEquals(mapOf("a" to p("A [a].mka")), plan.register)
    }

    @Test
    fun `a file listed under another name than recorded is recorded with the new one`() {
        val plan = DownloadScanPlan.of(
            recorded = mapOf("a" to p("A [a].mka")),
            listed = mapOf("a" to p("A [a].mka (1)")),
            inside = emptySet(), spared = emptySet(), checked = emptyMap(),
        )
        assertEquals(mapOf("a" to p("A [a].mka (1)")), plan.register)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `a file the folder lists but that has no path on the phone keeps its song downloaded`() {
        // A folder on another app's storage (the picker's Downloads root, a cloud folder): the scan
        // threw "Hello darkness my old friend" for each file and the song lost its download.
        val plan = DownloadScanPlan.of(
            recorded = mapOf("kept" to null),
            listed = mapOf("kept" to null, "new" to null),
            inside = emptySet(), spared = emptySet(), checked = emptyMap(),
        )
        assertEquals(mapOf("new" to null), plan.register)
        assertEquals(emptySet<String>(), plan.remove)
    }

    @Test
    fun `a listing that came back empty forgets nothing whose own file is there`() {
        // dvd's case: every liked song in the folder, none of them in the listing.
        val recorded = (1..397).associate { "id$it" to p("Song $it [id$it].mka") }
        val plan = DownloadScanPlan.of(
            recorded = recorded,
            listed = emptyMap(),
            inside = emptySet(), spared = emptySet(),
            checked = recorded.mapValues { FolderFileCheck.THERE },
        )
        assertEquals(emptySet<String>(), plan.remove)
        assertEquals(emptyMap<String, String?>(), plan.register)
    }

    @Test
    fun `a song whose own file is gone loses its download, one that could not be told keeps it`() {
        val plan = DownloadScanPlan.of(
            recorded = mapOf("gone" to p("G [gone].mka"), "unknown" to p("U [unknown].mka"), "there" to p("T [there].mka")),
            listed = emptyMap(),
            inside = emptySet(), spared = emptySet(),
            checked = mapOf("gone" to FolderFileCheck.GONE, "unknown" to FolderFileCheck.UNKNOWN, "there" to FolderFileCheck.THERE),
        )
        assertEquals(setOf("gone"), plan.remove)
    }

    @Test
    fun `a song recorded inside the app goes once media3 no longer has it, and stays while it does`() {
        val plan = DownloadScanPlan.of(
            recorded = mapOf("inside" to null, "stale" to null),
            listed = emptyMap(),
            inside = setOf("inside"), spared = emptySet(), checked = emptyMap(),
        )
        assertEquals(setOf("stale"), plan.remove)
    }

    @Test
    fun `a song under a folder that could not be listed is left alone`() {
        val plan = DownloadScanPlan.of(
            recorded = mapOf("card" to "/storage/1234-5678/Music/C [card].mka"),
            listed = emptyMap(),
            inside = emptySet(), spared = setOf("card"), checked = emptyMap(),
        )
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `only songs not accounted for otherwise, and with a path, are looked at one by one`() {
        val look = DownloadScanPlan.toLookAt(
            recorded = mapOf(
                "listed" to p("L [listed].mka"),
                "inside" to null,
                "spared" to "/storage/1234-5678/S [spared].mka",
                "missing" to p("M [missing].mka"),
                "noPath" to null,
            ),
            listed = setOf("listed"),
            inside = setOf("inside"),
            spared = setOf("spared"),
        )
        assertEquals(mapOf("missing" to p("M [missing].mka")), look)
    }

    @Test
    fun `only a look that says gone, or no file to look at, ends a download`() {
        assertTrue(DownloadScanPlan.goes(FolderFileCheck.GONE))
        assertTrue(DownloadScanPlan.goes(null))
        assertFalse(DownloadScanPlan.goes(FolderFileCheck.THERE))
        assertFalse(DownloadScanPlan.goes(FolderFileCheck.UNKNOWN))
    }
}
