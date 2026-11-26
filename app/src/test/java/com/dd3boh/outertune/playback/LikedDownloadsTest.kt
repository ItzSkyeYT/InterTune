/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #64: taking back a like should take back the download the liked-songs setting made, and only
 * that one. A song downloaded by hand stays, whatever happens to its like.
 */
class LikedDownloadsTest {
    private fun song(id: String, liked: Boolean) = SongEntity(id = id, title = id, localPath = null, liked = liked)

    @Test
    fun `an unlike removes only what the setting downloaded, and only with the switch on`() {
        val made = setOf("a", "b")
        val songs = listOf(song("a", liked = false), song("b", liked = true), song("c", liked = false))
        // b is still liked, c was downloaded by hand
        assertEquals(listOf("a"), LikedDownloads.toRemove(syncOn = true, made = made, songs = songs))
        assertEquals(emptyList<String>(), LikedDownloads.toRemove(syncOn = false, made = made, songs = songs))
        assertEquals(emptyList<String>(), LikedDownloads.toRemove(syncOn = true, made = emptySet(), songs = songs))
    }

    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()

    @Test
    fun `the setting remembers its own downloads, and forgets a song whose download goes by any means`() {
        // remembered only when the request really went out, and only for the setting's downloads;
        // a download by hand forgets it, so taking back the like never takes that one
        assertTrue("if (sent) {\n            if (byLikes) rememberLikedDownload(id) else forgetLikedDownload(id)\n        }" in util)
        assertTrue("eligible.forEach { downloadSong(it.id, it.title, byLikes = true) }" in util)
        assertTrue("if (!downloadSong(song.id, song.title, byLikes = true)) {" in util)
        // the sync's catch up downloads as the setting too: its liked songs from YouTube were never on the list
        assertTrue("pending.forEach { downloadSong(it.id, it.title, byLikes = true) }" in util)
        // a removal of any kind, and a catch up stopped halfway, forget it
        assertTrue(util.substringAfter("fun removeDownload(id: String) {").trimStart().startsWith("forgetLikedDownload(id)"))
        assertTrue("forgetLikedDownload(id)\n                    sends.attempt(\"removing \$id\")" in util)
        // and so does a download that fails or goes any other way: the notification's Cancel, Clear all downloads
        val listener = util.substringAfter("override fun onDownloadChanged(")
        // First in that branch; what else goes with a failure (DownloadSays) may follow it.
        assertTrue(listener.contains("if (download.state == Download.STATE_FAILED || download.state == Download.STATE_REMOVING) {\n                        forgetLikedDownload(download.request.id)\n"))
    }

    /**
     * 26 Nov 2026 (the whole-app scan): a liked song the setting had downloaded, then downloaded by
     * hand with a playlist's Download, stayed the setting's, since downloadSong returned for a song
     * already there before it handed it over; unliking it later deleted it. Asked for by hand, a
     * song already downloaded or already in a download folder is the person's.
     */
    @Test
    fun `a song already downloaded and asked for by hand becomes the person's`() {
        val download = util.substringAfter("private fun downloadSong(id: String, title: String, byLikes: Boolean = false): Boolean {")
        assertTrue(download, "if (!isNotDownloaded(id)) {\n            if (!byLikes) forgetLikedDownload(id)\n            return true\n        }" in download)
        assertTrue(download, "if (keepFolderFile(id, title, look = false)) {\n            if (!byLikes) forgetLikedDownload(id)\n            return true\n        }" in download)
    }

    @Test
    fun `every way of taking back a like asks, behind the flag and the switch`() {
        assertTrue("unlikeRemovesDownloads(songs.filter { !it.liked })" in util)
        assertTrue("if (!Unreleased.LIKED_SYNC || unliked.isEmpty() || likedAutodownload == LikedAutodownloadMode.OFF || !likedSync) return" in util)
        // the selection menu's Remove all likes, which used to call nothing
        val selection = File("src/main/java/com/dd3boh/outertune/ui/menu/SelectionSongsMenu.kt").readText()
        assertTrue(selection.substringAfter("if (allLiked) {").substringBefore("} else {").contains("downloadUtil.autoDownloadOnLike(unliked)"))
        // and a like taken back on YouTube, which the liked sync brings
        val sync = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()
        val unlikes = sync.substringAfter("// Unlike local songs in the database").substringBefore("// Insert or like songs in the database")
        assertTrue(unlikes.contains("val unliked = songsToUnlike.map { it.song.localToggleLike() }"))
        assertTrue(unlikes.contains("downloadUtil.autoDownloadOnLike(unliked)"))
    }
}
