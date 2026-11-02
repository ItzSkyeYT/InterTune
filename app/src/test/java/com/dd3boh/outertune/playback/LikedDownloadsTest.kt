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
        // remembered only when the request really went out, and only for the setting's downloads
        assertTrue("if (sent && byLikes) rememberLikedDownload(id)" in util)
        assertTrue("eligible.forEach { downloadSong(it.id, it.title, byLikes = true) }" in util)
        assertTrue("if (!downloadSong(song.id, song.title, byLikes = true)) {" in util)
        // a removal of any kind, and a catch up stopped halfway, forget it
        assertTrue(util.substringAfter("fun removeDownload(id: String) {").trimStart().startsWith("forgetLikedDownload(id)"))
        assertTrue("forgetLikedDownload(id)\n                    sends.attempt(\"removing \$id\")" in util)
    }

    @Test
    fun `every way of taking back a like asks, behind the flag and the switch`() {
        assertTrue("unlikeRemovesDownloads(songs.filter { !it.liked })" in util)
        assertTrue("if (!Unreleased.LIKED_SYNC || unliked.isEmpty() || likedAutodownload == LikedAutodownloadMode.OFF || !likedSync) return" in util)
        // the selection menu's Remove all likes, which used to call nothing
        val selection = File("src/main/java/com/dd3boh/outertune/ui/menu/SelectionSongsMenu.kt").readText()
        assertTrue(selection.substringAfter("if (allLiked) {").substringBefore("} else {").contains("downloadUtil.autoDownloadOnLike(unliked)"))
    }
}
