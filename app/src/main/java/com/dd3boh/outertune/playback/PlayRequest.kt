/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * A request from Android Auto or another browser to play a song it was shown, read back from the
 * ids MediaLibrarySessionCallback hands out: "song/<song>", "artist/<artist>/<song>",
 * "album/<album>/<song>", "playlist/<playlist>/<song>" and "search/<query>/<song>".
 *
 * The song is always the last part and the list is everything between the first slash and the
 * last. A search query can hold slashes of its own, and splitting on every slash read "AC/DC" as
 * the query "AC" and the song "DC", so the wrong song played.
 */
sealed interface PlayRequest {
    val songId: String

    data class Song(override val songId: String) : PlayRequest
    data class Artist(val artistId: String, override val songId: String) : PlayRequest
    data class Album(val albumId: String, override val songId: String) : PlayRequest
    data class Playlist(val playlistId: String, override val songId: String) : PlayRequest
    data class Search(val query: String, override val songId: String) : PlayRequest

    companion object {
        /** The request an id names, or null for anything that is not one of the ids above. */
        fun parse(mediaId: String): PlayRequest? {
            if ('/' !in mediaId) return null
            val kind = mediaId.substringBefore('/')
            val rest = mediaId.substringAfter('/')
            val songId = rest.substringAfterLast('/')
            if (songId.isEmpty()) return null
            if (kind == MusicService.SONG) return if ('/' in rest) null else Song(songId)
            val list = rest.substringBeforeLast('/', missingDelimiterValue = "")
            if (list.isEmpty()) return null
            return when (kind) {
                MusicService.ARTIST -> Artist(list, songId)
                MusicService.ALBUM -> Album(list, songId)
                MusicService.PLAYLIST -> Playlist(list, songId)
                MusicService.SEARCH -> Search(list, songId)
                else -> null
            }
        }
    }
}
