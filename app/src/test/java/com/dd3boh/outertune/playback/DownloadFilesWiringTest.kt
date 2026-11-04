/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.playback.downloadManager.knownPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Downloads saved as song files other apps use (Unreleased.DOWNLOAD_FILES): where they are made,
 * how the app knows them again, and how the player reads them.
 */
class DownloadFilesWiringTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val folder = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/DirectoryMangerOt.kt").readText()

    @Test
    fun `with the flag, a download becomes a song file first and the stream only when it cannot be one`() {
        val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("fun cd()")
        val existing = migrate.indexOf("localMgr.getFilePathIfExists(s.key)")
        val songFile = migrate.indexOf("?: (if (Unreleased.DOWNLOAD_FILES) saveSongFile(s.key) else null)")
        val stream = migrate.indexOf("localMgr.saveFile(s.key, stream, displayName)")
        assertTrue(existing in 0 until songFile)
        assertTrue(songFile < stream)
    }

    @Test
    fun `a song file is made from the cache's own files and the song's row`() {
        val save = util.substringAfter("private suspend fun saveSongFile(id: String)").substringBefore("private fun songTags(")
        assertTrue(save.contains("downloadCache.getCachedSpans(id)"))
        assertTrue(save.contains("SongFiles.save(files, songTags(song))"))
        assertTrue("a failure falls back to the stream", save.contains(".getOrNull()"))
    }

    @Test
    fun `a full scan knows song files by the database's paths, read before it clears them`() {
        val scan = util.substringAfter("private suspend fun scanDownloadsLocked()").substringBefore("companion object")
        val listed = scan.indexOf("localMgr.getAvailableFiles(false, knownPaths(database.downloadedOrQueuedSongs().first()))")
        assertTrue(listed > 0)
        assertTrue(listed < scan.indexOf("database.removeAllDownloadedSongs()"))
        assertTrue("a rescan too", folder.contains("getAvailableFiles(false, knownPaths(mediaId))"))
    }

    @Test
    fun `the player and the folder find a file by what it is, not by the old name rule alone`() {
        assertTrue(folder.contains("return availableFiles.find { idOf(it) == mediaId }"))
        assertTrue("tags are read only with the flag", folder.contains("if (!Unreleased.DOWNLOAD_FILES) continue"))
    }

    @Test
    fun `a song file is played past the song cache`() {
        assertTrue(service.contains(".setKey(downloadedKey(mediaId, resolved.file.name)).build()"))
        val sink = service.substringAfter("HybridCacheDataSinkFactory(playerCache) { dataSpec ->").substringBefore(".setFlags(")
        assertTrue(sink.contains("dataSpec.key?.startsWith(SONG_FILE_KEY) != true"))
        assertEquals("songfile:dQw4w9WgXcQ", downloadedKey("dQw4w9WgXcQ", "Rick Astley - Song.opus"))
        assertEquals("songfile:dQw4w9WgXcQ", downloadedKey("dQw4w9WgXcQ", "Rick Astley - Song.m4a"))
        assertEquals("a download saved as the stream keeps its id", "dQw4w9WgXcQ", downloadedKey("dQw4w9WgXcQ", "Song [dQw4w9WgXcQ].mka"))
        assertEquals("dQw4w9WgXcQ", downloadedKey("dQw4w9WgXcQ", null))
    }

    @Test
    fun `a path two songs claim says nothing`() {
        fun song(id: String, path: String?) = Song(SongEntity(id, "t", localPath = path), artists = emptyList())
        val paths = knownPaths(
            listOf(song("aaaaaaaaaaa", "/m/A.opus"), song("bbbbbbbbbbb", "/m/B.opus"), song("ccccccccccc", "/m/B.opus"), song("ddddddddddd", null))
        )
        assertEquals(mapOf("/m/A.opus" to "aaaaaaaaaaa"), paths)
    }
}
