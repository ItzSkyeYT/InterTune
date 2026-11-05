/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import com.dd3boh.outertune.db.PlaylistSql
import com.dd3boh.outertune.db.SchemaDb
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection

/**
 * Taking copies out, and Undo, go the ways the app already has: the heart, Remove from library,
 * Remove from playlist and Add to playlist. So a signed-in account hears of a copy taken out of
 * Liked exactly as it hears of a heart tapped off, and nothing new can reach it.
 *
 * The playlist's order is run against the exported schema with the real move, as PlaylistSqlTest
 * does; the rest reads the source, as the flag tests do, because the paths are the point.
 */
class SavedTwiceShelfTest {

    // ---- the heart is a toggle: only what is as expected is turned ----

    @Test
    fun `a copy is turned only while it is as the screen saw it`() {
        val liked = mutableMapOf("a" to true, "b" to false, "c" to true)
        val turned = turnEach(listOf("a", "b", "c"), isNow = { liked[it] == true }) { liked[it] = !liked.getValue(it) }
        // b was taken out by hand meanwhile: tapping its heart again would have liked it.
        assertEquals(listOf("a", "c"), turned)
        assertEquals(mapOf("a" to false, "b" to false, "c" to false), liked)
        // Undo turns back those it took, and only those still out.
        liked["c"] = true
        val back = turnEach(turned, isNow = { liked[it] == false }) { liked[it] = !liked.getValue(it) }
        assertEquals(listOf("a"), back)
        assertEquals(mapOf("a" to true, "b" to false, "c" to true), liked)
    }

    // ---- the playlist's own order, through the real move ----

    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO playlist(id, name) VALUES ('P', 'P')")
        for (song in listOf("X", "A", "Y", "B", "Z", "W")) {
            exec("INSERT INTO song(id, title, duration, liked) VALUES ('$song', '$song', 200, 0)")
        }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun int(sql: String): Int? = db.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getInt(1) else null } }

    /** The rows as PlaylistsDao writes them: the same SQL, Room's named parameters filled in. */
    private val rows = object : PlaylistRows {
        override fun remove(mapId: Int): PlaylistSongMap? {
            val (songId, position) = db.createStatement().use { st ->
                st.executeQuery(PlaylistSql.MAP_BY_ID.replace(":id", "$mapId")).use { rs ->
                    if (!rs.next()) return null
                    rs.getString("songId") to rs.getInt("position")
                }
            }
            move(position, Int.MAX_VALUE)
            exec("DELETE FROM playlist_song_map WHERE id = $mapId")
            return PlaylistSongMap(id = mapId, playlistId = "P", songId = songId, position = position)
        }

        override fun add(songId: String): Int {
            // PlaylistsDao.nextPlaylistPosition, then addSongToPlaylist's insert.
            val at = int("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song_map WHERE playlistId = 'P'")!!
            exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', '$songId', $at)")
            return at
        }

        override fun move(from: Int, to: Int) {
            exec(PlaylistSql.MOVE.replace(":playlistId", "'P'").replace(":fromPosition", "$from").replace(":toPosition", "$to"))
        }
    }

    /** Fills the playlist and gives each row's id, in order. */
    private fun fill(vararg songs: String): List<Int> = songs.mapIndexed { position, song ->
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', '$song', $position)")
        int("SELECT MAX(id) FROM playlist_song_map")!!
    }

    private fun playlist(): List<Pair<String, Int>> = db.createStatement().use { st ->
        st.executeQuery("SELECT songId, position FROM playlist_song_map WHERE playlistId = 'P' ORDER BY position").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
        }
    }

    private fun inOrder(vararg songs: String) = songs.mapIndexed { i, s -> s to i }

    @Test
    fun `copies taken out of a playlist leave no gap, and Undo puts each back where it was`() {
        val ids = fill("X", "A", "Y", "B", "Z", "W")
        val taken = takeOutRows(rows, listOf(ids[1], ids[3]))
        assertEquals(inOrder("X", "Y", "Z", "W"), playlist())
        putBackRows(rows, taken)
        assertEquals(inOrder("X", "A", "Y", "B", "Z", "W"), playlist())
    }

    @Test
    fun `in whatever order they were taken`() {
        val ids = fill("X", "A", "Y", "B", "Z", "W")
        val taken = takeOutRows(rows, listOf(ids[5], ids[0], ids[3]))
        assertEquals(inOrder("A", "Y", "Z"), playlist())
        putBackRows(rows, taken)
        assertEquals(inOrder("X", "A", "Y", "B", "Z", "W"), playlist())
    }

    @Test
    fun `the same song twice, the copy picked goes and the other stays where it is`() {
        val ids = fill("X", "A", "Y", "A", "Z")
        val taken = takeOutRows(rows, listOf(ids[3]))
        assertEquals(inOrder("X", "A", "Y", "Z"), playlist())
        putBackRows(rows, taken)
        assertEquals(inOrder("X", "A", "Y", "A", "Z"), playlist())
    }

    @Test
    fun `a row already gone is neither taken nor brought back`() {
        val ids = fill("X", "A", "Y", "B")
        rows.remove(ids[1])
        val taken = takeOutRows(rows, listOf(ids[1], ids[3]))
        assertEquals(listOf("B"), taken.map { it.songId })
        putBackRows(rows, taken)
        assertEquals(inOrder("X", "Y", "B"), playlist())
    }

    @Test
    fun `a playlist that got shorter meanwhile gets the song back at its end, with no gap`() {
        val ids = fill("X", "A", "Y", "B", "Z")
        val taken = takeOutRows(rows, listOf(ids[4]))
        rows.remove(ids[0])
        rows.remove(ids[1])
        putBackRows(rows, taken)
        assertEquals(inOrder("Y", "B", "Z"), playlist())
    }

    @Test
    fun `a song that cannot go back is left out, no other row moves, and only what went back is said`() {
        val ids = fill("X", "A", "Y", "B", "Z")
        val taken = takeOutRows(rows, listOf(ids[1], ids[3]))
        val refusing = object : PlaylistRows by rows {
            override fun add(songId: String): Int = if (songId == "A") -1 else rows.add(songId)
        }
        assertEquals(listOf("B"), putBackRows(refusing, taken))
        assertEquals(inOrder("X", "Y", "B", "Z"), playlist())
    }

    // ---- the paths, as written ----

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    /** The text without its comments, so a sentence naming a call is not taken for the call. */
    private fun code(path: String) = source(path)
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val shelf = code("savedtwice/SavedTwiceShelf.kt")
    private fun shelfClass(name: String) = shelf.substringAfter("class $name(").substringBefore("\nprivate class ").substringBefore("\n/**")

    @Test
    fun `out of Liked goes the way the heart goes, and Undo taps it on again`() {
        val liked = shelfClass("LikedShelf")
        assertTrue("taken out with the player's heart", "database.turnLike(it, downloadUtil)" in liked)
        // Both ways the same toggle, guarded by the like as it is now.
        assertTrue("liked now, so the heart takes it off", "heart(copies.map { it.songId }.distinct(), likedNow = true)" in liked)
        assertTrue("not liked now, so Undo puts it on", "heart(taken, likedNow = false)" in liked)
        // turnLike is the heart: the player's service and the widget tap it through the same function.
        assertTrue(Regex("""turnLike\(\s*it,\s*downloadUtil\s*\)""").containsMatchIn(code("playback/MusicService.kt")))
        assertTrue("turnLike(" in code("widget/WidgetActions.kt"))
        // And turnLike sends the like to YouTube through the row's own toggle, as it always has.
        assertTrue("songRow(id)?.toggleLike()" in code("playback/SongLike.kt"))
    }

    @Test
    fun `out of the library goes the way Remove from library goes, and only YouTube songs`() {
        val library = shelfClass("LibraryShelf")
        assertTrue("database.update(it.toggleLibrary())" in library)
        assertTrue("never a file on the phone", "it.inLibrary != null && !it.isLocal" in library)
        val menu = code("ui/menu/SongMenu.kt")
        val removeFromLibrary = menu.substringAfter("title = R.string.remove_from_library").substringBefore("}\n            }")
        assertTrue("the menu's Remove from library is the same toggle", "update(song.song.toggleLibrary())" in removeFromLibrary)
        assertTrue("and the menu offers it for YouTube songs only", "if (!song.song.isLocal) {\n            if (song.song.inLibrary == null) {" in menu)
    }

    @Test
    fun `out of a playlist goes the way Remove from playlist goes, and Undo the way Add to playlist goes`() {
        assertTrue("database.removeFromPlaylist(mapId)" in shelfClass("StoredRows"))
        val playlist = shelfClass("PlaylistShelf")
        assertTrue("removeFromYouTubePlaylist(playlist.playlist.browseId, it)" in playlist)
        assertTrue("pushToYouTube(playlist, " in playlist)
        assertTrue("database.addSongToPlaylist(playlist, listOf(songId))" in shelfClass("StoredRows"))
        // The song menu's Remove from playlist calls the same two.
        val menu = code("ui/menu/SongMenu.kt")
        val removeFromPlaylist = menu.substringAfter("title = R.string.remove_from_playlist").substringBefore("onDismiss()")
        assertTrue("removeFromPlaylist(playlistSong.map.id)" in removeFromPlaylist)
        assertTrue("removeFromYouTubePlaylist(playlist?.playlist?.browseId, playlistSong.map)" in removeFromPlaylist)
        // And Add to playlist adds and pushes with the same two.
        val add = code("ui/dialog/AddToPlaylistDialog.kt")
        assertTrue("database.addSongToPlaylist(playlist, ids)" in add)
        assertTrue("pushToYouTube(playlist, written)" in add)
        // And as it does since #62, only what went in is pushed.
        assertTrue("pushToYouTube(playlist, back)" in playlist)
    }

    @Test
    fun `nothing is deleted from the phone`() {
        for (path in listOf("savedtwice/SavedTwiceShelf.kt", "savedtwice/SavedTwice.kt", "savedtwice/SavedTwicePlace.kt", "ui/screens/SavedTwiceScreen.kt", "viewmodels/SavedTwiceViewModel.kt")) {
            val text = code(path)
            for (call in listOf("removeDownload", ".delete()", "deleteRecursively", "File(", "delete(song", "deleteSong")) {
                assertFalse("$path calls $call", call in text)
            }
        }
    }
}
