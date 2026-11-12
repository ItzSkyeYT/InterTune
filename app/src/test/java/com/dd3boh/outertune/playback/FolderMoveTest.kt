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

    /** One move, its steps written down in the order they ran. */
    private class Move(
        val written: String? = "content://tree/Music/document/Music%2FSong%20%5Bid%5D.mka",
        val path: String? = "/storage/emulated/0/Music/Song [id].mka",
        val recordFails: Boolean = false,
    ) {
        val steps = mutableListOf<String>()

        fun run(): FolderMove = moveIntoFolder(
            save = { steps += "write"; written },
            pathOf = { steps += "path of $it"; path },
            record = { steps += "record $it"; if (recordFails) error("the database is full") },
            dropCopy = { steps += "drop the copy in the app" },
        )
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
