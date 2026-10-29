/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import androidx.compose.ui.util.fastSumBy
import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import java.time.ZoneOffset

/**
 * The order of a folder's songs in the Folders tab, with no Android in it, so it is tested.
 */
object FolderSongOrder {

    /**
     * Songs that are equal on the sort picked keep the album's own order (see [albumOrder]),
     * whichever way the list is turned. They used to keep the order the library handed them over
     * in, which follows the songs' random ids: sorted by artist, by release date or by play count,
     * an album's songs came out shuffled (issue #39).
     */
    fun sorted(songs: List<Song>, sortType: FolderSongSortType, descending: Boolean): List<Song> {
        val key: Comparator<Song> = when (sortType) {
            FolderSongSortType.TRACK_NUMBER -> return byTrack(songs, descending)
            FolderSongSortType.CREATE_DATE -> compareBy { it.song.inLibrary?.toEpochSecond(ZoneOffset.UTC) ?: -1L }
            FolderSongSortType.MODIFIED_DATE -> compareBy { it.song.getDateModifiedLong() ?: -1L }
            FolderSongSortType.RELEASE_DATE -> compareBy { it.song.getDateLong() ?: -1L }
            FolderSongSortType.NAME -> compareBy { it.song.title.lowercase() }
            FolderSongSortType.ARTIST -> compareBy { it.artists.joinToString { artist -> artist.name }.lowercase() }
            FolderSongSortType.PLAY_COUNT -> compareBy { it.playCount?.fastSumBy { count -> count.count }?.toLong() ?: 0L }
        }
        return songs.sortedWith((if (descending) key.reversed() else key).then(albumOrder))
    }

    /**
     * An album's own order: disc by disc, then track, as numbers, then title, and the file's path
     * where all of those are the same. Songs with no track number come after the numbered ones, by
     * title. A song with no disc number is on disc 1.
     */
    private val albumOrder: Comparator<Song> = compareBy(
        { if (track(it) == null) 1 else 0 },
        { if (track(it) == null) 0 else disc(it) },
        { track(it) ?: 0 },
        { it.song.title.lowercase() },
        { it.song.localPath.orEmpty() },
    )

    /**
     * The album's order, turned round when [descending]. Songs with no track number come last
     * whichever way the list is turned.
     */
    private fun byTrack(songs: List<Song>, descending: Boolean): List<Song> {
        val (numbered, unnumbered) = songs.partition { track(it) != null }
        val inOrder = numbered.sortedWith(albumOrder)
        return (if (descending) inOrder.reversed() else inOrder) + unnumbered.sortedWith(albumOrder)
    }

    // 0 is no number, as TagLib has it, and older MediaStore scans stored 0 for a missing tag.
    private fun track(song: Song): Int? = song.song.trackNumber?.takeIf { it > 0 }

    private fun disc(song: Song): Int = song.song.discNumber?.takeIf { it > 0 } ?: 1
}
