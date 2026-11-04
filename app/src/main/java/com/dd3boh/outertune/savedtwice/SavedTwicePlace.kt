/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The list a Saved twice screen is opened from. Each one counts only its own songs: the liked
 * ones, the ones in the library, or one playlist's, never every song the app has stored.
 */
sealed interface SavedTwicePlace {

    /** Taken out the way the heart takes a like back. */
    data object Liked : SavedTwicePlace

    /**
     * Taken out the way Remove from library does it. A file on the phone is left out: the app has
     * no way to take one out of the library, and the next scan would put it back anyway.
     */
    data object Library : SavedTwicePlace

    /** A playlist the app lets you change. Taken out the way Remove from playlist does it. */
    data class Playlist(val id: String) : SavedTwicePlace

    /** Where the screen is, for the navigation. */
    val route: String
        get() = when (this) {
            Liked -> "$ROUTE/$LIKED"
            Library -> "$ROUTE/$LIBRARY"
            is Playlist -> "$ROUTE/$PLAYLIST?playlist=$id"
        }

    companion object {
        const val ROUTE = "saved_twice"
        const val LIKED = "liked"
        const val LIBRARY = "library"
        const val PLAYLIST = "playlist"

        /** The place a route names, or null for one it does not. */
        fun of(place: String?, playlist: String?): SavedTwicePlace? = when (place) {
            LIKED -> Liked
            LIBRARY -> Library
            PLAYLIST -> playlist?.takeIf { it.isNotBlank() }?.let(::Playlist)
            else -> null
        }
    }
}

private fun LocalDateTime?.seconds(): Long? = this?.toEpochSecond(ZoneOffset.UTC)

/** A stored song as a copy for the rule. [key] is the song's id unless a playlist row names it. */
fun Song.savedTwiceCopy(key: String = id, savedAt: Long?): SavedTwice.Copy {
    val albumName = album?.title?.takeIf { it.isNotBlank() } ?: song.albumName?.takeIf { it.isNotBlank() }
    return SavedTwice.Copy(
        key = key,
        songId = id,
        title = song.title,
        artists = artists.map { it.name },
        duration = song.duration,
        album = albumName,
        kind = SavedTwice.kindOf(song.isLocal, albumName, song.thumbnailUrl),
        savedAt = savedAt,
    )
}

/** The liked songs among [songs], saved when they were liked. */
fun likedCopies(songs: List<Song>): List<SavedTwice.Copy> =
    songs.filter { it.song.liked }.map { it.savedTwiceCopy(savedAt = it.song.likedDate.seconds()) }

/** The songs in the library among [songs], files on the phone left out (see [SavedTwicePlace.Library]). */
fun libraryCopies(songs: List<Song>): List<SavedTwice.Copy> =
    songs.filter { it.song.inLibrary != null && !it.song.isLocal }.map { it.savedTwiceCopy(savedAt = it.song.inLibrary.seconds()) }

/**
 * A playlist's rows, each its own copy even where a song is in twice. A row's id grows with every
 * row written, so it orders them by when they were added.
 */
fun playlistCopies(rows: List<PlaylistSong>): List<SavedTwice.Copy> =
    rows.map { it.song.savedTwiceCopy(key = it.map.id.toString(), savedAt = it.map.id.toLong()) }
