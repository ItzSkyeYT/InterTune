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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    // idsSparedByUnlistedFolders: only a folder whose permission this install holds spares its songs (#278)

    /** This phone's card, picked here. */
    private val card = "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic%2FInterTune"

    /** The old phone's card, picked there and come with a backup: this phone has no such card and no permission for it. */
    private val oldCard = "content://com.android.externalstorage.documents/tree/9A1B-2C3D%3AMusic%2FInterTune"

    @Test
    fun `a folder not listed whose permission is held spares its songs, as a card that is out`() {
        val spared = idsSparedByUnlistedFolders(
            mapOf("onCard" to "/storage/1234-5678/Music/InterTune/a.mka", "elsewhere" to "/storage/emulated/0/Music/b.mka"),
            unlisted = mapOf(card to "/storage/1234-5678/Music/InterTune"),
            held = listOf(main, card),
        )
        assertEquals(setOf("onCard"), spared)
    }

    @Test
    fun `a folder from another phone, with no permission here, spares nothing`() {
        val songs = mapOf(
            "restored" to "/storage/9A1B-2C3D/Music/InterTune/a.mka",
            "here" to "/storage/emulated/0/Music/InterTune/b.mka",
        )
        // Its card is not on this phone, so its path is not known: as a root, that covered every song.
        assertEquals(emptySet<String>(), idsSparedByUnlistedFolders(songs, unlisted = mapOf(oldCard to null), held = listOf(main)))
        assertEquals(
            emptySet<String>(),
            idsSparedByUnlistedFolders(songs, unlisted = mapOf(oldCard to "/storage/9A1B-2C3D/Music/InterTune"), held = emptyList()),
        )
    }

    @Test
    fun `with the old phone's folder and this phone's card both not listed, only the card spares`() {
        val spared = idsSparedByUnlistedFolders(
            mapOf("restored" to "/storage/9A1B-2C3D/Music/InterTune/a.mka", "onCard" to "/storage/1234-5678/Music/InterTune/b.mka"),
            unlisted = mapOf(oldCard to null, card to "/storage/1234-5678/Music/InterTune"),
            held = listOf(card),
        )
        assertEquals(setOf("onCard"), spared)
    }

    @Test
    fun `a song kept inside the app is never touched by the rule`() {
        for (held in listOf(listOf(card), emptyList(), null)) {
            assertEquals(emptySet<String>(), idsSparedByUnlistedFolders(mapOf("inside" to null), mapOf(card to null), held))
        }
    }

    @Test
    fun `permissions that could not be read spare under every folder not listed, as before`() {
        val songs = mapOf("restored" to "/storage/9A1B-2C3D/Music/InterTune/a.mka", "inside" to null)
        assertEquals(setOf("restored"), idsSparedByUnlistedFolders(songs, unlisted = mapOf(oldCard to null), held = null))
    }

    @Test
    fun `a permission on a tree holds for the folder known by its top document`() {
        assertTrue(sameFolder(card, "$card/document/1234-5678%3AMusic%2FInterTune"))
        assertTrue(sameFolder(card, "content://com.android.externalstorage.documents/tree/1234-5678:Music%2FInterTune"))
    }

    @Test
    fun `another folder, its parent, another provider or no address is not the same folder`() {
        assertFalse(sameFolder(card, oldCard))
        assertFalse(sameFolder(card, "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic"))
        assertFalse(sameFolder(card, "content://com.example.documents/tree/1234-5678%3AMusic%2FInterTune"))
        assertFalse(sameFolder(card, "not an address"))
    }

    @Test
    fun `both scans spare through the permission check`() {
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val rescan = util.substringAfter("private suspend fun rescanDownloadsLocked()").substringBefore("suspend fun scanDownloads()")
        val scan = util.substringAfter("private suspend fun scanDownloadsLocked()").substringBefore("companion object")
        for (body in listOf(rescan, scan)) {
            assertTrue(body.contains("val spared = sparedUnderUnlistedFolders("))
            assertFalse(body.contains("idsCoveredByUnlistedRoot("))
        }
        val helper = util.substringAfter("private fun sparedUnderUnlistedFolders(").substringBefore("private suspend fun rescanDownloadsLocked()")
        assertTrue(helper.contains("contentResolver.persistedUriPermissions"))
        assertTrue(helper.contains("idsSparedByUnlistedFolders(idToLocalPath, folders, held)"))
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
    fun `no main folder set never counts itself, and its extra folders count as with one`() {
        // The extra folders are read without a main folder now (Inside the app keeps the folder
        // as one of them, InsideKeepsFolder): one that would not open spares its songs as it
        // would with a main folder, rather than every one of them leaving Downloads.
        val dirs = unlistedDownloadDirs(
            main = "", mainOpened = false,
            extras = listOf(e1), unopenedExtras = listOf(e1), unlistable = listOf(e2)
        )
        assertEquals(listOf(e1, e2), dirs)
        val none = unlistedDownloadDirs(
            main = "", mainOpened = false,
            extras = emptyList(), unopenedExtras = emptyList(), unlistable = emptyList()
        )
        assertEquals(emptyList<String>(), none)
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
