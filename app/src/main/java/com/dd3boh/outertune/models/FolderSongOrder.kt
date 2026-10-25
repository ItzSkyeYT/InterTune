/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import androidx.compose.ui.util.fastSumBy
import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.numberToAlpha
import java.time.ZoneOffset

/**
 * The order of a folder's songs in the Folders tab, with no Android in it, so it is tested.
 */
object FolderSongOrder {

    fun sorted(songs: List<Song>, sortType: FolderSongSortType, descending: Boolean): List<Song> {
        val sorted = when (sortType) {
            FolderSongSortType.TRACK_NUMBER -> return byTrack(songs, descending)
            FolderSongSortType.CREATE_DATE -> songs.sortedBy { numberToAlpha(it.song.inLibrary?.toEpochSecond(ZoneOffset.UTC) ?: -1L) }
            FolderSongSortType.MODIFIED_DATE -> songs.sortedBy { numberToAlpha(it.song.getDateModifiedLong() ?: -1L) }
            FolderSongSortType.RELEASE_DATE -> songs.sortedBy { numberToAlpha(it.song.getDateLong() ?: -1L) }
            FolderSongSortType.NAME -> songs.sortedBy { it.song.title.lowercase() }
            FolderSongSortType.ARTIST -> songs.sortedBy { it.artists.joinToString { artist -> artist.name }.lowercase() }
            FolderSongSortType.PLAY_COUNT -> songs.sortedBy { numberToAlpha((it.playCount?.fastSumBy { it.count })?.toLong() ?: 0L) }
        }
        return if (descending) sorted.reversed() else sorted
    }

    /**
     * Disc by disc, then track, as numbers, and by title where both are the same. A song with no
     * disc number is on disc 1. Songs with no track number come last, by title, whichever way the
     * list is turned.
     */
    private fun byTrack(songs: List<Song>, descending: Boolean): List<Song> {
        val (numbered, unnumbered) = songs.partition { track(it) != null }
        val inOrder = numbered.sortedWith(compareBy({ disc(it) }, { track(it) }, { it.song.title.lowercase() }))
        return (if (descending) inOrder.reversed() else inOrder) + unnumbered.sortedBy { it.song.title.lowercase() }
    }

    // 0 is no number, as TagLib has it, and older MediaStore scans stored 0 for a missing tag.
    private fun track(song: Song): Int? = song.song.trackNumber?.takeIf { it > 0 }

    private fun disc(song: Song): Int = song.song.discNumber?.takeIf { it > 0 } ?: 1
}
