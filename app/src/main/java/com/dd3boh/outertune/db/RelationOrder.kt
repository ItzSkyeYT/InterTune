/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistCoverSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap

/**
 * Puts what three of Room's relations bring in the order their own rows give.
 *
 * Room reads a song's artists, an album's songs and a playlist's first songs through views that
 * sort them (sorted_song_artist_map, sorted_song_album_map, playlist_song_map_preview). Since
 * 3.37.0 SQLite drops a view's ORDER BY once the view is joined, and every Android from 14 on ships
 * such a SQLite (14: 3.39 and 3.42, 15: 3.44, 16 QPR2 and 17: 3.50). The rows then came in the
 * order of an index: a song's artists by their ids, so BbY WOW read "Judeline, KAROL G, rusowsky"
 * and its lyrics were looked up under Judeline, and an album's songs and a playlist's first songs
 * in the order they were stored.
 *
 * So Song, AlbumWithSongs and Playlist each have a constructor of Room's own that also takes those
 * rows (song_artist_map, song_album_map, the playlist's preview) and puts the relation in order
 * with these. The views stay as they are: nothing changes in the database.
 */
object RelationOrder {
    /** [artists] in the order the song credits them. One that [credits] does not place goes last. */
    fun artists(artists: List<ArtistEntity>, credits: List<SongArtistMap>): List<ArtistEntity> =
        byPlace(artists, credits.associate { it.artistId to it.position }) { it.id }

    /** [songs] in the album's order, by the index each one has in it. */
    fun songs(songs: List<Song>, tracks: List<SongAlbumMap>): List<Song> =
        byPlace(songs, tracks.associate { it.songId to it.index }) { it.id }

    /** The pictures of a playlist's first songs, in the playlist's order. A song with none gives null. */
    fun coverPictures(cover: List<PlaylistCoverSong>): List<String?> =
        cover.sortedBy { it.position }.map { it.thumbnailUrl.firstOrNull() }

    /** [items] by the place [places] gives their key. Those with none keep their order, after the rest. */
    private fun <T> byPlace(items: List<T>, places: Map<String, Int>, key: (T) -> String): List<T> =
        if (items.size < 2) items else items.sortedBy { places[key(it)] ?: Int.MAX_VALUE }
}
