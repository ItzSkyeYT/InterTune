/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.downloadManager.childDocumentId
import com.dd3boh.outertune.playback.downloadManager.fatName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song whose file is in the download folder keeps its download, and is never fetched again and
 * written a second time beside itself (dvd, 20 Nov 2026: a scan forgot all 397 of his liked songs,
 * the liked catch up fetched every one again, and the move after it wrote "Title [id].mka (1)"
 * beside each). Read from the source, as DownloadFilesWiringTest does: the decisions themselves are
 * tested in DownloadScanPlanTest.
 */
class DownloadKeepsFolderFilesTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val folder = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()
    private val tree = File("src/main/java/androidx/documentfile/provider/TreeDocumentFileOt.java").readText()

    private val scan = util.substringAfter("private suspend fun scanDownloadsLocked()").substringBefore("companion object")
    private val rescan = util.substringAfter("private suspend fun rescanDownloadsLocked()").substringBefore("suspend fun scanDownloads()")
    private val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("fun cd()")

    @Test
    fun `a full scan never clears every download to record them again`() {
        assertFalse(scan.contains("removeAllDownloadedSongs()"))
        assertFalse("a file with no path is recorded without one", scan.contains("throw (InvalidAudioFileException("))
        val looked = scan.indexOf("localMgr.lookAgain(DownloadScanPlan.toLookAt(")
        val planned = scan.indexOf("DownloadScanPlan.of(recorded, listed, inside.keys, spared, checked)")
        val written = scan.indexOf("database.transactionNow {")
        assertTrue("the songs the listing missed are looked at before the decision", looked in 0 until planned)
        assertTrue("and the decision is made before anything is written", planned < written)
        // One transaction for the removals and the records: a write that fails leaves every song as it was.
        assertEquals(1, Regex("""database\.transactionNow \{""").findAll(scan).count())
        val block = scan.substring(written)
        assertTrue(block.contains("plan.remove.forEach { removeDownloadSong(it) }"))
        assertTrue(block.contains("if (path != null) registerDownloadSong(id, timeNow, path) else updateDownloadStatus(id, timeNow)"))
    }

    @Test
    fun `a scan that fails halfway does not leave the downloads marked as being processed`() {
        // Left at true, every liked catch up after it returned at once ("never race a scan").
        val body = scan.substringAfter("isProcessingDownloads.value = true").substringBefore("rescanDownloadsLocked()")
        assertTrue(body.trimStart().startsWith("try {"))
        assertTrue(body.contains("} finally {\n            isProcessingDownloads.value = false\n        }"))
    }

    @Test
    fun `the rescan at each start looks at a missing song's own file before it lets it go`() {
        val looked = rescan.indexOf("localMgr.lookAgain(")
        val kept = rescan.indexOf("missingFiles.removeIf { !DownloadScanPlan.goes(checked[it.song.id]) }")
        val shed = rescan.indexOf("removeDownloadSong(it.song.id)")
        assertTrue(looked in 0 until kept)
        assertTrue(kept < shed)
        // After the songs media3 still holds are taken out, so that only the real candidates are asked about.
        assertTrue(rescan.indexOf("downloadManager.downloadIndex.getDownloads()") in 0 until looked)
    }

    @Test
    fun `a move keeps the song's own file already in the folder instead of writing a second`() {
        val listed = migrate.indexOf("localMgr.getFilePathIfExists(s.key)")
        val there = migrate.indexOf("?: localMgr.fileAlreadyThere(s.key, displayName, song?.song?.localPath)")
        val songFile = migrate.indexOf("saveSongFile(s.key, notice)")
        val stream = migrate.indexOf("localMgr.saveFile(s.key, stream, displayName)")
        assertTrue(listed in 0 until there)
        assertTrue("before anything is written, a song file included", there < songFile)
        assertTrue(songFile < stream)
    }

    @Test
    fun `no download and no liked catch up fetches a song whose file is in the folder`() {
        val download = util.substringAfter("private fun downloadSong(id: String").substringBefore("val downloadRequest =")
        assertTrue("from memory only: a button calls it on the main thread", download.contains("if (keepFolderFile(id, title, look = false)) {"))
        val settings = util.substringAfter("fun startLikedDownloads(").substringBefore("val batch =")
        assertTrue(settings.contains(".filterNot { keepFolderFile(it.id, it.title, look = true) }"))
        val sync = util.substringAfter("suspend fun downloadLikedSongs(").substringBefore("val pending =")
        assertTrue(sync.contains("withContext(Dispatchers.IO) { notDownloaded.filterNot { keepFolderFile(it.id, it.title, look = true) } }"))
        // Recorded as the scan records a listed file, the map first, after scanTouchedIds.
        val keep = util.substringAfter("private fun keepFolderFile(").substringBefore("\n    }\n")
        assertTrue(keep.indexOf("scanTouchedIds.add(id)") in 0 until keep.indexOf("downloads.update { it + (id to now) }"))
        assertTrue(keep.contains("database.registerDownloadSong(id, now, path) else database.updateDownloadStatus(id, now)"))
    }

    @Test
    fun `a file found by a look of its own joins the folder's list, under the same lock as a delete`() {
        val adopt = folder.substringAfter("private fun adopt(").substringBefore("\n    }\n")
        assertTrue(adopt.contains("synchronized(this)"))
        assertTrue(folder.contains("@Synchronized\n    fun deleteFile(mediaId: String): FileRemoval {"))
        val look = folder.substringAfter("fun lookAgain(").substringBefore("private fun adopt(")
        assertTrue("found: in the list", look.contains("adopt(id, found)"))
        // FileRemoval's rule: gone only when the folder answers.
        assertTrue(look.contains("answers.getOrPut(root.path) { root.dir.exists() }"))
    }

    @Test
    fun `the folder's listing reads each column by its name`() {
        val list = tree.substringAfter("public DocumentFile @Nullable [] listFilesOrNull()").substringBefore("private static void closeQuietly(")
        assertTrue(list.contains("columnOr(c, DocumentsContract.Document.COLUMN_DOCUMENT_ID, 0)"))
        assertTrue(list.contains("columnOr(c, DocumentsContract.Document.COLUMN_DISPLAY_NAME, 1)"))
        assertTrue(list.contains("columnOr(c, DocumentsContract.Document.COLUMN_MIME_TYPE, 2)"))
        assertFalse(list.contains("c.getString(1)"))
        assertFalse(list.contains("c.getString(2)"))
    }

    @Test
    fun `a file's document id under a folder of the phone's own storage`() {
        assertEquals("primary:Music/InterTune/Song [abc].mka", childDocumentId("primary:Music/InterTune", "Song [abc].mka"))
        assertEquals("primary:Song [abc].mka", childDocumentId("primary:", "Song [abc].mka"))
        assertEquals("1234-5678:Music/x.mka", childDocumentId("1234-5678:Music", "x.mka"))
    }

    @Test
    fun `a name as the phone's storage writes it`() {
        assertEquals("What_ [abc].mka", fatName("What? [abc].mka"))
        assertEquals("AC_DC - T.N.T. [abc].mka", fatName("AC/DC - T.N.T. [abc].mka"))
        assertEquals("a_b_c_d_e_f_g_h_i_ [x].mka", fatName("a\"b*c/d:e<f>g?h\\i| [x].mka"))
        assertEquals("KATAMARI [xm4xk-GE6rY].mka", fatName("KATAMARI [xm4xk-GE6rY].mka"))
        assertEquals("Ça va, été [id].mka", fatName("Ça va, été [id].mka"))
    }
}
