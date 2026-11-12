/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.savedtwice.SavedTwice
import com.dd3boh.outertune.savedtwice.SavedTwicePlace
import com.dd3boh.outertune.savedtwice.SavedTwiceShelf
import com.dd3boh.outertune.savedtwice.libraryCopies
import com.dd3boh.outertune.savedtwice.likedCopies
import com.dd3boh.outertune.savedtwice.playlistCopies
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Saved twice screen: the songs of one list saved more than once, read once when the screen
 * opens so that nothing moves under the finger while copies are being picked, and the pick made
 * in each.
 */
@HiltViewModel
class SavedTwiceViewModel @Inject constructor(
    private val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val place: SavedTwicePlace? = SavedTwicePlace.of(savedStateHandle.get<String>("place"), savedStateHandle.get<String>("playlist"))

    /** What the screen shows. [songs] are the stored songs by copy key, for their covers and names. */
    data class Shown(
        val loaded: Boolean = false,
        val groups: List<SavedTwice.Group> = emptyList(),
        val songs: Map<String, Song> = emptyMap(),
        /** The playlist's name, for a playlist. */
        val placeName: String? = null,
    )

    private val _shown = MutableStateFlow(Shown())
    val shown: StateFlow<Shown> = _shown.asStateFlow()

    /** The copy kept in each group, by group id, where somebody picked another than the default. */
    private val _picks = MutableStateFlow<Map<String, String>>(emptyMap())
    val picks: StateFlow<Map<String, String>> = _picks.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val read = when (val place = place) {
                // Newest first, as the lists open.
                SavedTwicePlace.Liked -> database.likedSongs(SongSortType.CREATE_DATE, true).first().let { songs ->
                    Triple(likedCopies(songs), songs.associateBy { it.id }, null)
                }
                SavedTwicePlace.Library -> database.songs(SongSortType.CREATE_DATE, true).first().let { songs ->
                    Triple(libraryCopies(songs), songs.associateBy { it.id }, null)
                }
                is SavedTwicePlace.Playlist -> database.playlistSongs(place.id).first().let { rows ->
                    Triple(playlistCopies(rows), rows.associate { it.map.id.toString() to it.song }, database.playlist(place.id).first()?.playlist?.name)
                }
                null -> Triple(emptyList(), emptyMap(), null)
            }
            val groups = withContext(Dispatchers.Default) { SavedTwice.groups(read.first) }
            _shown.value = Shown(loaded = true, groups = groups, songs = read.second, placeName = read.third)
        }
    }

    fun pick(group: SavedTwice.Group, copy: SavedTwice.Copy) = _picks.update { it + (group.id to copy.key) }

    /** The copy kept in [group] now. */
    fun kept(group: SavedTwice.Group, picks: Map<String, String>): String =
        picks[group.id]?.takeIf { key -> group.copies.any { it.key == key } } ?: group.keptByDefault.key

    /** Every copy not kept, in every group. */
    fun toTakeOut(): List<SavedTwice.Copy> = SavedTwice.toTakeOut(shown.value.groups, picks.value)

    /** The list's own way of taking copies out, which outlives this screen for its Undo. */
    fun shelf(): SavedTwiceShelf? = place?.let { SavedTwiceShelf.of(it, database, downloadUtil) }
}
