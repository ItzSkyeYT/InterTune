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
import java.io.File

/**
 * A download moved into the download folder, once a batch is done or by Migrate, keeps a copy that
 * plays at every moment of the move.
 *
 * The copy inside the app was deleted before anything pointed to the new file: the song's row
 * learnt it only from the full scan after the whole batch, the queue's copy of the song never did,
 * and a Migrate cut short by leaving its page skipped that scan. Until then the song played from
 * the network, or offline not at all, and Remove download did nothing on it.
 */
class FolderMoveTest {

    /**
     * One move, its steps written down in the order they ran. [removedAfter]: the step after which the song's download
     * is removed (Remove download, by hand or with the like), or null when it is not.
     */
    private class Move(
        val written: String? = "content://tree/Music/document/Music%2FSong%20%5Bid%5D.mka",
        val path: String? = "/storage/emulated/0/Music/Song [id].mka",
        val recordFails: Boolean = false,
        val removedAfter: String? = null,
    ) {
        val steps = mutableListOf<String>()
        private var removed = false

        private fun step(name: String) {
            steps += name
            if (removedAfter != null && name.startsWith(removedAfter)) removed = true
        }

        fun run(): FolderMove = moveIntoFolder(
            save = { step("write"); written },
            pathOf = { step("path of $it"); path },
            record = { step("record $it"); if (recordFails) error("the database is full") },
            dropCopy = { step("drop the copy in the app") },
            removed = { removed },
            unsave = { step("delete $it") },
            unrecord = { step("take the row back") },
        )
    }

    @Test
    fun `a download removed while its file was made leaves no file, no row, and its copy to media3`() {
        // An unlike during the minute an MP3 takes: the file landed in the folder and in other apps' lists, recorded as
        // downloaded while every screen said it was not (28 Nov 2026).
        val move = Move(removedAfter = "write")
        assertEquals(FolderMove.REMOVED, move.run())
        assertEquals(listOf("write", "delete content://tree/Music/document/Music%2FSong%20%5Bid%5D.mka"), move.steps)
    }

    @Test
    fun `a download removed while its row was written takes the row back too`() {
        val move = Move(removedAfter = "record")
        assertEquals(FolderMove.REMOVED, move.run())
        assertEquals(
            listOf(
                "write",
                "path of content://tree/Music/document/Music%2FSong%20%5Bid%5D.mka",
                "record /storage/emulated/0/Music/Song [id].mka",
                "delete content://tree/Music/document/Music%2FSong%20%5Bid%5D.mka",
                "take the row back",
            ),
            move.steps,
        )
        assertFalse("media3's removal drops the copy, never the move", "drop the copy in the app" in move.steps)
    }

    @Test
    fun `a download removed once it has moved is the removal's to delete`() {
        // Remove download finds the file in the folder's list then, and deletes it as any download's.
        val move = Move(removedAfter = "drop")
        assertEquals(FolderMove.MOVED, move.run())
        assertFalse(move.steps.any { it.startsWith("delete") })
    }

    @Test
    fun `a file that could not be written for a removed download has nothing to delete`() {
        val move = Move(written = null, removedAfter = "write")
        assertEquals(FolderMove.NOT_WRITTEN, move.run())
        assertEquals(listOf("write"), move.steps)
    }

    @Test
    fun `the file is whole and the row points to it before the copy in the app goes`() {
        val move = Move()
        assertEquals(FolderMove.MOVED, move.run())
        assertEquals(
            listOf(
                "write",
                "path of content://tree/Music/document/Music%2FSong%20%5Bid%5D.mka",
                "record /storage/emulated/0/Music/Song [id].mka",
                "drop the copy in the app",
            ),
            move.steps,
        )
    }

    @Test
    fun `a file that could not be written leaves the song as it was`() {
        val move = Move(written = null)
        assertEquals(FolderMove.NOT_WRITTEN, move.run())
        assertEquals(listOf("write"), move.steps)
    }

    @Test
    fun `a file with no path on the phone keeps the copy in the app`() {
        // A folder on another app's storage: the scan cannot register its file either, and without
        // the copy the song was no longer downloaded at all.
        val move = Move(path = null)
        assertEquals(FolderMove.NO_PATH, move.run())
        assertFalse("drop the copy in the app" in move.steps)
    }

    @Test
    fun `a row that could not be written keeps the copy in the app`() {
        val move = Move(recordFails = true)
        assertEquals(FolderMove.NOT_RECORDED, move.run())
        assertFalse("drop the copy in the app" in move.steps)
    }

    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val folder = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val page = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DownloadsFrag.kt").readText()

    @Test
    fun `each song of a batch or a Migrate moves through those steps`() {
        val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("fun cd()")
        val move = migrate.indexOf("moveIntoFolder(")
        val record = migrate.indexOf("record = { path -> database.registerDownloadSong(s.key, stateToLocalDateTime(s.value), path) }")
        val drop = migrate.indexOf("dropCopy = {")
        assertTrue("the move goes through moveIntoFolder", move > 0)
        assertTrue("the row gets the file's path, with the date the download landed", record > move)
        assertTrue(drop > record)
        // The copy in the app goes only inside dropCopy, after the row.
        assertEquals(1, Regex("""downloadCache\.removeResource\(s\.key\)""").findAll(migrate).count())
        assertTrue(migrate.indexOf("downloadCache.removeResource(s.key)") > drop)
        assertEquals(1, Regex("""migratedIndex\.removeDownload\(s\.key\)""").findAll(migrate).count())
        assertTrue(migrate.indexOf("migratedIndex.removeDownload(s.key)") > drop)
    }

    @Test
    fun `Remove download stops the song's move and its conversion, and a new request for the song lifts that`() {
        assertTrue(util.contains("private val removedSinceAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()"))
        // Every Remove download, by hand or with the like, marks the song before the folder or media3 hear of it.
        val remove = util.substringAfter("fun removeDownload(id: String) {").substringBefore("\n    }\n")
        val mark = remove.indexOf("removedSinceAsked.add(id)")
        assertTrue(mark in 0 until remove.indexOf("deleteSong(id)"))
        assertTrue(mark < remove.indexOf("DownloadService.sendRemoveDownload("))
        // A new request takes the mark away, once it has reached media3.
        val download = util.substringAfter("private fun downloadSong(id: String").substringBefore("\n    }\n")
        assertTrue(download.contains("if (sent) removedSinceAsked.remove(id)"))
        assertTrue(download.indexOf("if (sent) removedSinceAsked.remove(id)") > download.indexOf("val sent = sends.attempt("))
        // The conversion stops between two pieces (SongConverter asks), and the move's other steps follow moveIntoFolder.
        val save = util.substringAfter("private suspend fun saveSongFile(id: String").substringBefore("\n    }\n")
        assertTrue(save.contains("{ job?.isActive == false || id in removedSinceAsked }"))
        val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("private suspend fun saveSongFile(")
        val skip = migrate.indexOf("if (s.key in removedSinceAsked) {")
        assertTrue("a song removed before its move is not copied at all", skip in 0 until migrate.indexOf("openFromCache(downloadCache, s.key)"))
        assertTrue(migrate.contains("removed = { s.key in removedSinceAsked },"))
        assertTrue("its file goes as a removed download's does", migrate.substringAfter("unsave = {").substringBefore("},").contains("localMgr.deleteFile(s.key)"))
        assertTrue(migrate.substringAfter("unrecord = {").substringBefore("},").contains("database.removeDownloadSong(s.key)"))
        // The stop of one song's conversion ends that song's move only; a stop of the move itself still ends the move.
        val stopped = migrate.substringAfter("} catch (e: CancellationException) {").substringBefore("when (moved) {")
        assertTrue(stopped.contains("if (s.key !in removedSinceAsked || !currentCoroutineContext().isActive) throw e"))
        assertTrue(stopped.contains("FolderMove.REMOVED"))
        assertTrue(migrate.contains("FolderMove.REMOVED -> {"))
    }

    @Test
    fun `a file saved as the stream is in the folder's list at once, as a song file is`() {
        // The player finds a folder download through the list, and Remove download deletes through it.
        val save = folder.substringAfter("fun saveFile(").substringBefore("fun saveSongFile(")
        assertTrue(save.contains("addAvailable(TreeDocumentFileOt(null, context, newFile.uri, newFile.name ?: fileName, \"audio/mka\"))"))
        val songFile = folder.substringAfter("fun saveSongFile(").substringBefore("private fun documentId(")
        assertTrue(songFile.contains("addAvailable(saved)"))
        // Under the same lock as deleteFile, so a save and a delete at the same moment cannot undo each other.
        assertTrue(folder.contains("@Synchronized\n    private fun addAvailable(file: DocumentFile) {"))
        assertTrue(folder.contains("@Synchronized\n    fun deleteFile(mediaId: String): FileRemoval {"))
    }

    @Test
    fun `Migrate outlives the Storage page`() {
        val dialog = page.substringAfter("if (showMigrationDialog) {")
        assertTrue(dialog.contains("downloadUtil.startMigrate()"))
        assertFalse("on the page's scope, leaving the page cancelled it halfway", dialog.contains("downloadUtil.migrateDownloads()"))
        val start = util.substringAfter("fun startMigrate() {").substringBefore("\n    }\n")
        assertTrue(start.contains("CoroutineScope(dlCoroutine).launch { migrateDownloads() }"))
    }

    @Test
    fun `the player asks the folder when the download cache misses, whatever the queue's copy of the song says`() {
        val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long").substringBefore("val isCache =")
        val cacheMiss = resolve.indexOf("val isDownload =")
        val asked = resolve.indexOf("if (!isDownload && !isLocal && localPath == null) {")
        assertTrue(cacheMiss > 0)
        assertTrue("after the download cache, before the song cache and the stream", asked > cacheMiss)
        val lookup = resolve.substring(asked)
        assertTrue(lookup.contains("downloadUtil.localMgr.isExists(mediaId)?.takeIf { it.exists() }"))
        assertTrue(lookup.contains("return ResolvedSong.Downloaded(it)"))
    }
}
