/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.turnLike
import com.dd3boh.outertune.ui.dialog.pushToYouTube
import com.dd3boh.outertune.utils.removeFromYouTubePlaylist
import com.dd3boh.outertune.utils.syncCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a take out did, and how to bring back exactly what it took. [count] is how many copies
 * went: a copy that had already gone by the time of the tap is not counted, nor brought back.
 */
class Taken(val count: Int, val putBack: suspend () -> Unit)

/**
 * Taking a song's extra copies out of the list they are saved in, each the way the app already
 * does it by hand, so a signed-in account hears of it exactly as it does today. Nothing here
 * deletes a file, a download or a song's row: a copy only leaves a list.
 */
interface SavedTwiceShelf {
    /** Takes out those of [copies] that are still in the list. Reads and writes the database, so off the main thread. */
    suspend fun takeOut(copies: List<SavedTwice.Copy>): Taken

    companion object {
        fun of(place: SavedTwicePlace, database: MusicDatabase, downloadUtil: DownloadUtil): SavedTwiceShelf = when (place) {
            SavedTwicePlace.Liked -> LikedShelf(database, downloadUtil)
            SavedTwicePlace.Library -> LibraryShelf(database)
            is SavedTwicePlace.Playlist -> PlaylistShelf(database, place.id)
        }
    }
}

/**
 * Turns each of [items] that [isNow] still finds as expected, and returns those. A copy somebody
 * already took out by hand, or put back, between the screen opening and the tap is left alone:
 * the heart is a toggle, and turning it blindly would put back a like that was just taken away.
 */
fun <T> turnEach(items: List<T>, isNow: (T) -> Boolean, turn: (T) -> Unit): List<T> = items.filter(isNow).onEach(turn)

/**
 * Liked songs: the heart, tapped off, through the function the player's heart and the widget's use
 * ([turnLike]): the row stored without its like and the unlike sent to YouTube Music when the app
 * may write to the account. Undo taps it on again the same way.
 */
private class LikedShelf(private val database: MusicDatabase, private val downloadUtil: DownloadUtil) : SavedTwiceShelf {

    override suspend fun takeOut(copies: List<SavedTwice.Copy>): Taken = withContext(Dispatchers.IO) {
        val taken = heart(copies.map { it.songId }.distinct(), likedNow = true)
        Taken(taken.size) { withContext(Dispatchers.IO) { heart(taken, likedNow = false) } }
    }

    private fun heart(ids: List<String>, likedNow: Boolean): List<String> =
        turnEach(ids, isNow = { database.songRow(it)?.liked == likedNow }) { database.turnLike(it, downloadUtil) }
}

/**
 * The library: Remove from library, as the song menu does it, the row's own toggle written back.
 * That never reaches YouTube, and it takes a like with it on this phone, so Undo writes back what
 * it changed: in the library since the same day, and the like as it was.
 */
private class LibraryShelf(private val database: MusicDatabase) : SavedTwiceShelf {

    override suspend fun takeOut(copies: List<SavedTwice.Copy>): Taken = withContext(Dispatchers.IO) {
        val rows = copies.map { it.songId }.distinct().mapNotNull { database.songRow(it) }
        val before = turnEach(rows, isNow = { it.inLibrary != null && !it.isLocal }) { database.update(it.toggleLibrary()) }
        Taken(before.size) { withContext(Dispatchers.IO) { putBack(before) } }
    }

    private fun putBack(before: List<SongEntity>) {
        for (was in before) {
            val now = database.songRow(was.id) ?: continue
            if (now.inLibrary == null) database.update(now.copy(inLibrary = was.inLibrary, liked = was.liked, likedDate = was.likedDate))
        }
    }
}

/**
 * A playlist's rows as a take out and its Undo use them, each step the app's own: Remove from
 * playlist's, Add to playlist's and a drag's.
 */
interface PlaylistRows {
    /** Takes one row out as Remove from playlist does. The row as it was, or null when it has gone. */
    fun remove(mapId: Int): PlaylistSongMap?

    /**
     * Adds the song at the end as Add to playlist does, and says at which position; -1 when it
     * could not go in (the database leaves out a song that is no longer on the phone, #62).
     */
    fun add(songId: String): Int

    /** Moves the row at [from] to [to], as a drag does. */
    fun move(from: Int, to: Int)
}

/** Takes out the rows [mapIds] names that are still there, and returns them as they were. */
fun takeOutRows(rows: PlaylistRows, mapIds: List<Int>): List<PlaylistSongMap> = mapIds.mapNotNull { rows.remove(it) }

/**
 * Puts back what [takeOutRows] took: each song added as Add to playlist does, then moved back to
 * where it was. Last out, first back: each row left the playlist as it stood after the ones taken
 * before it, so its position is right again once those taken after it are back. A playlist that
 * got shorter meanwhile gets the song at its end rather than a gap. Returns the songs that went
 * back, in the order they did: one that could not is left out, and no other row moves for it.
 */
fun putBackRows(rows: PlaylistRows, removed: List<PlaylistSongMap>): List<String> {
    val back = ArrayList<String>()
    for (was in removed.asReversed()) {
        val at = rows.add(was.songId)
        if (at < 0) continue
        back += was.songId
        val to = minOf(was.position, at)
        if (to != at) rows.move(at, to)
    }
    return back
}

/** [PlaylistRows] as they are stored, for a caller already in a transaction. */
private class StoredRows(private val database: MusicDatabase, private val playlist: Playlist) : PlaylistRows {
    override fun remove(mapId: Int): PlaylistSongMap? = database.removeFromPlaylist(mapId)

    override fun add(songId: String): Int {
        if (database.addSongToPlaylist(playlist, listOf(songId)).isEmpty()) return -1
        return database.nextPlaylistPosition(playlist.id) - 1
    }

    override fun move(from: Int, to: Int) = database.move(playlist.id, from, to)
}

/**
 * A playlist: Remove from playlist, as the song menu does it, each row found where it is now
 * ([com.dd3boh.outertune.db.daos.PlaylistsDao.removeFromPlaylist]) and the removal sent to
 * YouTube Music ([removeFromYouTubePlaylist]). Undo adds each song back the way Add to playlist
 * does, here and on YouTube Music, and moves it back to where it was here.
 */
private class PlaylistShelf(private val database: MusicDatabase, private val playlistId: String) : SavedTwiceShelf {

    override suspend fun takeOut(copies: List<SavedTwice.Copy>): Taken = withContext(Dispatchers.IO) {
        val playlist = database.playlist(playlistId).first() ?: return@withContext Taken(0) {}
        val removed = database.transactionNow {
            takeOutRows(StoredRows(this, playlist), copies.mapNotNull { it.key.toIntOrNull() })
        }
        // In the background, as the menu sends it, so the list changes at once whatever the network does.
        CoroutineScope(syncCoroutine).launch {
            removed.forEach { removeFromYouTubePlaylist(playlist.playlist.browseId, it) }
        }
        Taken(removed.size) {
            withContext(Dispatchers.IO) {
                val back = database.transactionNow { putBackRows(StoredRows(this, playlist), removed) }
                // At the end of the playlist on YouTube Music, as Add to playlist puts it there:
                // only what went back here, as Add to playlist pushes only what went in.
                pushToYouTube(playlist, back)
            }
        }
    }
}
