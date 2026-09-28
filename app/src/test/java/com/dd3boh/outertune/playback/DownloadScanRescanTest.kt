/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.downloadManager.unlistedDownloadDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class DownloadScanRescanTest {

    private fun t(epochSecond: Long) = Instant.ofEpochSecond(epochSecond).atZone(ZoneOffset.UTC).toLocalDateTime()

    // idsCoveredByUnlistedRoot

    @Test
    fun `nothing is covered when every folder could be listed`() {
        val covered = idsCoveredByUnlistedRoot(
            mapOf("a" to "/storage/emulated/0/Music/a.mka"),
            unlistedRoots = emptyList()
        )
        assertEquals(emptySet<String>(), covered)
    }

    @Test
    fun `a song under an unlisted folder is covered, a sibling folder is not`() {
        val covered = idsCoveredByUnlistedRoot(
            mapOf(
                "inside" to "/storage/emulated/0/Music/InterTune/a.mka",
                "sibling" to "/storage/emulated/0/Music2/b.mka",
                "notDownloaded" to null,
            ),
            unlistedRoots = listOf("/storage/emulated/0/Music")
        )
        assertEquals(setOf("inside"), covered)
    }

    @Test
    fun `a null root covers every song with a path, the same as the library scanner`() {
        val covered = idsCoveredByUnlistedRoot(
            mapOf("a" to "/storage/emulated/0/Anywhere/a.mka", "noPath" to null),
            unlistedRoots = listOf(null)
        )
        assertEquals(setOf("a"), covered)
    }

    // unlistedDownloadDirs

    private val main = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FInterTune"
    private val e1 = "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic"
    private val e2 = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test
    fun `a main folder that could not be opened counts with every extra folder`() {
        val dirs = unlistedDownloadDirs(
            main = main, mainOpened = false,
            extras = listOf(e1, main), unopenedExtras = emptyList(), unlistable = emptyList()
        )
        assertEquals(listOf(main, e1), dirs)
    }

    @Test
    fun `no main folder set means nothing counts as unlisted`() {
        val dirs = unlistedDownloadDirs(
            main = "", mainOpened = false,
            extras = listOf(e1), unopenedExtras = listOf(e1), unlistable = listOf(e2)
        )
        assertEquals(emptyList<String>(), dirs)
    }

    @Test
    fun `with the main folder open, an extra that could not be opened counts`() {
        val dirs = unlistedDownloadDirs(
            main = main, mainOpened = true,
            extras = listOf(e1, e2), unopenedExtras = listOf(e1), unlistable = emptyList()
        )
        assertEquals(listOf(e1), dirs)
    }

    @Test
    fun `with the main folder open, an unlistable folder and an unopened extra both count`() {
        val dirs = unlistedDownloadDirs(
            main = main, mainOpened = true,
            extras = listOf(e1, e2), unopenedExtras = listOf(e1), unlistable = listOf(main)
        )
        assertEquals(listOf(e1, main), dirs)
    }

    // mergeRescanResult

    @Test
    fun `a song that finished mid-walk is kept, not dropped by the stale snapshot`() {
        val snapshot = emptyMap<String, LocalDateTime>() // the walk started before it finished
        val live = mapOf("finished" to t(100)) // the listener recorded it in the meantime
        val merged = mergeRescanResult(snapshot, live, touchedIds = setOf("finished"))
        assertEquals(t(100), merged["finished"])
    }

    @Test
    fun `a song removed mid-walk (Clear all downloads included) is not put back with its old date`() {
        val snapshot = mapOf("removed" to t(50)) // the stale snapshot still has the old row
        val live = emptyMap<String, LocalDateTime>() // the listener already dropped it
        val merged = mergeRescanResult(snapshot, live, touchedIds = setOf("removed"))
        assertTrue(!merged.containsKey("removed"))
    }

    @Test
    fun `an id the listener never touched keeps the snapshot's value`() {
        val snapshot = mapOf("untouched" to t(10))
        val live = mapOf("untouched" to t(20)) // some unrelated later value, must not leak in
        val merged = mergeRescanResult(snapshot, live, touchedIds = emptySet())
        assertEquals(t(10), merged["untouched"])
    }

    // applyRescanResult

    @Test
    fun `a listener change landing while the merge reads the touched set is not overwritten`() {
        val downloads = MutableStateFlow<Map<String, LocalDateTime>>(mapOf("z" to t(1)))
        // Insertion-ordered, so the first read of the set sees "other" alone.
        val backing = linkedSetOf("other")
        var raced = false
        // The first read of the set stands in for the listener landing between the merge's read
        // and its write: it records "z" and moves its value on, as the listener does.
        val touched = object : AbstractMutableSet<String>() {
            override val size get() = backing.size
            override fun add(element: String) = backing.add(element)
            override fun iterator(): MutableIterator<String> {
                if (!raced) {
                    raced = true
                    backing.add("z")
                    downloads.update { it + ("z" to t(200)) }
                }
                return backing.iterator()
            }
        }

        applyRescanResult(downloads, mapOf("z" to t(1)), touched)

        assertEquals(t(200), downloads.value["z"])
        assertTrue(backing.isEmpty())
    }
}
