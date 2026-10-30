/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.content.Context
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.utils.mayPushToYouTube
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDateTime

/**
 * "Save as a playlist" on the year's card: the year's most played songs as a playlist kept on the
 * phone, and, as a second and separate choice, a copy of it on YouTube Music.
 *
 * The songs are the Recap's own ranking ([Recap.ranking], the tops and the rest in one order), so
 * the playlist is what the Recap showed, most played first, and nothing is read from the song table
 * but the rows of those songs. The playlist is local, as the queue's "Save queue as playlist" makes
 * one (Queue.kt): it is a record on this phone and nothing asks to put it anywhere. Each year has
 * one, found again by its id ([idOf]), so saving again updates it rather than making a second.
 * It has a cover of its own, drawn from the year card's art (RecapCards.saveCover) and kept in the
 * app's files, its address in the playlist's own picture column, which a local playlist leaves empty.
 *
 * The copy on YouTube Music is made only by the second button, only where the account may be
 * changed (signed in, sync set to read and write: mayPushToYouTube), with the calls the app already
 * makes for a synced playlist (YouTube.createPlaylist, then YouTube.addToPlaylist for each song, a
 * private playlist as all it creates are), one song at a time and in order. This file is the Recap's
 * one place for them (RecapPrivacyTest).
 */
object RecapPlaylist {
    /** At most this many songs: the year's most played. */
    const val SONGS = Recaps.PLAYLIST

    /** Three songs in a row that YouTube Music refuses end the push: it is not answering, and asking on would only hammer it. */
    const val GIVE_UP_AFTER = 3

    /**
     * The playlist of [year], always this one: the id is made from the year, so that a second save
     * finds the playlist the first made, even renamed by hand, and never one the listener made
     * themselves with the same name.
     */
    fun idOf(year: Int): String = "LP_RECAP_$year"

    /** The songs of the playlist from the Recap's [ranking]: in its order, a song once, [SONGS] at most. */
    fun songsOf(ranking: List<String>): List<String> = ranking.distinct().take(SONGS)

    /** The file name of the playlist's own cover: one for each year, drawn when the playlist is saved (RecapCards.saveCover). */
    fun coverName(year: Int): String = "recap-playlist-$year.jpg"

    /** A song of the playlist, as the writes need to know it: whether it is a file on the phone, which YouTube Music has no id for. */
    data class SongRow(val id: String, val local: Boolean)

    /** What saving needs of the database: Room's ([roomStore]) in the app, a made-up one in the tests. */
    interface Store {
        /** [block] as one transaction, on the calling thread. */
        fun <T> atomically(block: () -> T): T

        fun exists(playlistId: String): Boolean

        /** The rows of those of [ids] that are in the song table, in any order. */
        fun songs(ids: List<String>): List<SongRow>

        fun create(playlist: PlaylistEntity)

        /** Takes every song out of the playlist. */
        fun clear(playlistId: String)

        /** Gives the playlist the picture at [cover], if it has none. */
        fun setCoverIfNone(playlistId: String, cover: String)

        fun add(maps: List<PlaylistSongMap>)
    }

    /** The playlist as saved: its id, its songs in order, and whether this save made it (false: it was updated). */
    data class Saved(val id: String, val songs: List<SongRow>, val created: Boolean)

    /**
     * Saves the playlist of [year] with the songs of [ranking] that the database has, in the order
     * of the ranking. Made, with [name] and [cover] (the address of its own picture, null where none
     * could be drawn), if the year has none yet; otherwise its songs are replaced and its name, and
     * whatever else the listener has done to it, is left as it is: a playlist without a picture is
     * given [cover], one with a picture keeps it. Null where there is nothing to save. All of it in
     * one transaction; call it off the main thread.
     */
    fun save(store: Store, year: Int, name: String, ranking: List<String>, now: LocalDateTime = LocalDateTime.now(), cover: String? = null): Saved? {
        val wanted = songsOf(ranking)
        if (wanted.isEmpty()) return null
        return store.atomically {
            val found = store.songs(wanted).associateBy { it.id }
            // A song deleted since the Recap was worked out would break the map's foreign key.
            val songs = wanted.mapNotNull { found[it] }
            if (songs.isEmpty()) return@atomically null
            val id = idOf(year)
            val created = !store.exists(id)
            if (created) {
                store.create(PlaylistEntity(id = id, name = name, bookmarkedAt = now, isEditable = true, isLocal = true, thumbnailUrl = cover))
            } else {
                store.clear(id)
                if (cover != null) store.setCoverIfNone(id, cover)
            }
            store.add(songs.mapIndexed { position, song -> PlaylistSongMap(playlistId = id, songId = song.id, position = position) })
            Saved(id, songs, created)
        }
    }

    /** Room, in one transaction on the calling thread (MusicDatabase.transactionNow). */
    fun roomStore(database: MusicDatabase): Store = object : Store {
        override fun <T> atomically(block: () -> T): T = database.transactionNow { block() }
        override fun exists(playlistId: String) = database.playlistExists(playlistId)
        override fun songs(ids: List<String>) = database.songEntitiesByIds(ids).map { SongRow(it.id, it.isLocal) }
        override fun create(playlist: PlaylistEntity) = database.insert(playlist)
        override fun clear(playlistId: String) = database.clearPlaylist(playlistId)
        override fun setCoverIfNone(playlistId: String, cover: String) = database.setCoverIfNone(playlistId, cover)
        override fun add(maps: List<PlaylistSongMap>) = maps.forEach { database.insert(it) }
    }

    // ---- the copy on YouTube Music

    /** What the copy needs of YouTube Music: the app's own calls in the app, a made-up one in the tests. */
    interface Remote {
        suspend fun create(name: String): Result<String>
        suspend fun add(playlistId: String, songId: String): Result<Unit>
        suspend fun delete(playlistId: String): Result<Unit>
    }

    /** How the copy went: [added] of the playlist's [of] songs are on YouTube Music (files on the phone cannot be), or nothing was made. */
    sealed interface Pushed {
        data class Done(val added: Int, val of: Int) : Pushed
        data object Failed : Pushed
    }

    /** The app's own calls for a synced playlist, the only ones the Recap makes to the account. */
    private object YouTubeMusic : Remote {
        override suspend fun create(name: String): Result<String> = YouTube.createPlaylist(name)
        override suspend fun add(playlistId: String, songId: String): Result<Unit> = YouTube.addToPlaylist(playlistId, songId).map { }
        override suspend fun delete(playlistId: String): Result<Unit> = YouTube.deletePlaylist(playlistId).map { }
    }

    /**
     * Makes the copy on YouTube Music: a playlist called [name] with the songs of [songs] that are
     * not files on the phone. Only where the account may be changed; the account refuses anyway
     * where it may not (YouTube.accountWritesAllowed). [progress] is told how many songs have been
     * asked of the [of] to go, from 0.
     */
    suspend fun putOnYouTubeMusic(context: Context, name: String, songs: List<SongRow>, progress: (done: Int, of: Int) -> Unit = { _, _ -> }): Pushed {
        if (!context.mayPushToYouTube()) return Pushed.Failed
        return push(YouTubeMusic, name, songs, progress)
    }

    /**
     * The copy itself: the playlist is made first, then each song is added in turn, in the order of
     * [songs]. A song that is refused is passed over; [GIVE_UP_AFTER] in a row end it, and a
     * playlist left with nothing in it is taken away again, since it was made a moment ago.
     */
    suspend fun push(remote: Remote, name: String, songs: List<SongRow>, progress: (done: Int, of: Int) -> Unit = { _, _ -> }): Pushed {
        val going = songs.filterNot { it.local }.map { it.id }
        if (going.isEmpty()) return Pushed.Failed
        val playlistId = remote.create(name).getOrElse { return Pushed.Failed }
        var added = 0
        var refusedInARow = 0
        progress(0, going.size)
        for ((i, id) in going.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (remote.add(playlistId, id).isSuccess) {
                added++
                refusedInARow = 0
            } else if (++refusedInARow >= GIVE_UP_AFTER) {
                break
            }
            progress(i + 1, going.size)
        }
        if (added == 0) {
            remote.delete(playlistId)
            return Pushed.Failed
        }
        return Pushed.Done(added, songs.size)
    }
}
