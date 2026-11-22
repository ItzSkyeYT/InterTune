/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song from a file on the leader's phone plays in the room as the YouTube song that stands in for it, found by its
 * title, artist and length as the import finds songs (his ask of 22 Nov 2026): the guests used to sit on "song
 * unavailable" for it.
 */
class RoomStandInsTest {
    private val file = "LSa1b2c3d4"

    private fun song(title: String, artist: String, seconds: Int?, id: String) =
        SongItem(id = id, title = title, artists = listOf(Artist(name = artist, id = null)), duration = seconds, thumbnail = "")

    /** A search that answers [results] for each query, and remembers the queries. */
    private class Search(val results: (String) -> List<SongItem>) {
        val asked = ArrayList<String>()
        suspend fun run(query: String): List<SongItem> {
            asked += query
            return results(query)
        }
    }

    @Test
    fun `a YouTube song is itself in the room, a file has no id there until its stand-in is found`() = runBlocking {
        val search = Search { listOf(song("Hong Kong", "Gorillaz", 261, "yt-hongkong")) }
        val standIns = RoomStandIns(search::run)
        assertEquals("dQw4w9WgXcQ", standIns.roomId("dQw4w9WgXcQ", isLocal = false))
        assertNull(standIns.roomId(file, isLocal = true))
        assertTrue(standIns.toLookUp(file, isLocal = true))
        assertFalse("a YouTube song is never looked up", standIns.toLookUp("dQw4w9WgXcQ", isLocal = false))
        assertEquals("yt-hongkong", standIns.lookUp(file, "Hong Kong", "Gorillaz", 259))
        assertEquals("yt-hongkong", standIns.roomId(file, isLocal = true))
        assertFalse("found once, for the whole room", standIns.toLookUp(file, isLocal = true))
        assertEquals(listOf("Hong Kong Gorillaz"), search.asked)
    }

    @Test
    fun `only a sure match stands in, the same name at another length being a remix or a live take`() = runBlocking {
        val search = Search {
            listOf(
                song("Hong Kong (Live)", "Gorillaz", 302, "yt-live"),
                song("Hong Kong", "Gorillaz", 330, "yt-long-edit"),
            )
        }
        val standIns = RoomStandIns(search::run)
        assertNull(standIns.lookUp(file, "Hong Kong", "Gorillaz", 259))
        assertNull("the guests show it as unavailable, as before", standIns.roomId(file, isLocal = true))
        assertEquals("the title alone is tried as the import does", listOf("Hong Kong Gorillaz", "Hong Kong"), search.asked)
        assertFalse(standIns.toLookUp(file, isLocal = true))
    }

    @Test
    fun `a file with no artist tag is never asked about, its title being maybe its own name`() = runBlocking {
        val search = Search { listOf(song("mum's voicemail", "", 40, "yt-x")) }
        val standIns = RoomStandIns(search::run)
        assertNull(standIns.lookUp(file, "mum's voicemail", "", 40))
        assertNull(standIns.lookUp("LSz9y8x7w6", "", "Someone", 200))
        assertTrue("nothing was sent to YouTube", search.asked.isEmpty())
    }

    @Test
    fun `a search that fails is tried again a minute later, not with every word the leader sends`() = runBlocking {
        var now = 1_000_000L
        var down = true
        val search = Search { if (down) throw java.io.IOException("offline") else listOf(song("Hong Kong", "Gorillaz", 261, "yt-hongkong")) }
        val standIns = RoomStandIns(search::run) { now }
        assertNull(standIns.lookUp(file, "Hong Kong", "Gorillaz", 259))
        assertFalse(standIns.toLookUp(file, isLocal = true))
        now += RoomStandIns.RETRY_MS
        assertTrue(standIns.toLookUp(file, isLocal = true))
        down = false
        assertEquals("yt-hongkong", standIns.lookUp(file, "Hong Kong", "Gorillaz", 259))
    }

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()

    @Test
    fun `the leader's state names the stand-in, and the queue carries files only as theirs`() {
        val state = session.substringAfter("private fun sendState() {").substringBefore("\n    }\n")
        assertTrue("songId = standIns.roomId(item.mediaId, isLocal) ?: item.mediaId" in state)
        assertTrue("if (standIns.toLookUp(item.mediaId, isLocal)) lookUpStandIn(" in state)
        val queue = session.substringAfter("private fun ownQueue(): List<TogetherMessage.Song> {").substringBefore("\n    }\n")
        assertTrue("val id = standIns.roomId(item.mediaId, isLocal)\n            if (id != null) {" in queue)
        // A stand-in's picture is YouTube's own, never the file's path.
        assertTrue("thumbnail = if (id != item.mediaId) \"https://i.ytimg.com/vi/\$id/hqdefault.jpg\" else TogetherMessage.thumbnailFor(" in queue)
        val lookUp = session.substringAfter("private fun lookUpStandIn(").substringBefore("\n    }\n")
        assertTrue("if (Throttle.isBlocked) return" in lookUp)
        assertTrue("if (id != null && leading && service.exoPlayer.currentMediaItem?.mediaId == mediaId) {" in lookUp)
    }
}
