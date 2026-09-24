/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.QueueToPlaylist
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * A window onto [RecognitionEngine], plus saving what it heard as a playlist.
 *
 * The loop deliberately does not live here. A run has to survive the sheet being dismissed and the
 * screen going off, and a view model survives neither, so this only starts and stops the service
 * and passes the engine's state through to the sheet. Saving is here rather than in the engine
 * because it is a person's choice made on a screen, not part of listening.
 */
@HiltViewModel
class RecognitionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RecognitionEngine,
    private val history: RecognitionHistory,
    private val database: MusicDatabase,
) : ViewModel() {

    val state = engine.state
    val added = engine.added
    val continuous = engine.continuous
    val running = engine.running
    val skipped = engine.skipped
    val startedAt = engine.startedAt
    val nowPlaying = engine.nowPlaying
    val recognised = engine.recognised

    /** Everything ever heard, across restarts, newest first. */
    val heard = history.entries

    /** The playlist new songs are going into, once the list was saved as one or added to one. */
    val following = engine.following

    /** A mashup it could not tell from another, with the uploads it could be. */
    val mixChoice = engine.mixChoice

    fun acceptMix(song: SongItem) = engine.acceptMix(song)

    fun dismissMix() = engine.dismissMix()

    /**
     * A name for the playlist that no playlist in the library already has.
     *
     * The rule the queue sheet uses when it saves itself, so a second save on the same day gets a
     * " (2)" rather than becoming a second playlist nobody can tell from the first.
     */
    suspend fun proposePlaylistName(base: String): String = withContext(Dispatchers.IO) {
        val names = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
            .first().map { it.playlist.name }
        QueueToPlaylist.defaultName(base, names, base)
    }

    /**
     * Saves [songs] as a new local playlist, in the order given, and says whether it worked.
     *
     * The same writes the queue sheet makes when it saves itself as a playlist, and local for the
     * same reason: it is a record kept on this phone, and nothing here asks to put it on YouTube.
     */
    suspend fun saveAsPlaylist(name: String, songs: List<SongItem>): Boolean = withContext(Dispatchers.IO) {
        val playlist = PlaylistEntity(
            name = name,
            browseId = null,
            bookmarkedAt = LocalDateTime.now(),
            isEditable = true,
            isLocal = true,
        )
        runCatching {
            database.transactionNow {
                // Found by a YouTube search while listening, so most of these have never been in
                // the song table, and the map's foreign key needs them there first. insert skips
                // any that already are.
                songs.forEach { insert(it.toMediaMetadata()) }
                insert(playlist)
                QueueToPlaylist.positions(songs.map { it.id }).forEach { (songId, position) ->
                    insert(PlaylistSongMap(playlistId = playlist.id, songId = songId, position = position))
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not save the recognised songs as a playlist", it)
        }.onSuccess {
            // A new playlist, so every row in it is this save's.
            engine.follow(playlist, songs.map { it.id })
        }.isSuccess
    }

    /**
     * For the playlist picker: puts [songs] in the song table, which the playlist's foreign key
     * needs and a YouTube search result has never been in, and hands back the ids for the picker to
     * add. Its own duplicate check then deals with any already there. Following waits for [follow],
     * which the picker calls only once it has added: a cancelled duplicates prompt used to leave a
     * playlist followed that had received none of the list.
     */
    suspend fun prepareForPlaylist(playlist: Playlist, songs: List<SongItem>): List<String> =
        withContext(Dispatchers.IO) {
            val ids = songs.map { it.id }
            database.transactionNow { songs.forEach { insert(it.toMediaMetadata()) } }
            // Only the ones not there already are the picker's to write, and so this run's to take
            // out again should one turn out to be a piece of a mashup.
            pickerWrote = ids - database.playlistDuplicates(playlist.id, ids).toSet()
            ids
        }

    private var pickerWrote: List<String> = emptyList()

    /** Sends every song recognised from now on into [playlist] too. Once the picker has added. */
    fun follow(playlist: PlaylistEntity) = engine.follow(playlist, pickerWrote)

    /** New songs stay in the list but stop going into the playlist. */
    fun stopFollowing() = engine.follow(null)

    /**
     * The one guarded way in.
     *
     * The check lives here rather than at each screen so that no caller can start the microphone
     * without it, and so the two entry points cannot drift apart on the one thing that must not.
     * Refusing quietly is right: both screens ask for the permission themselves and only reach
     * this once it has been given, so arriving here without it means something else went wrong.
     */
    fun start(playlist: Playlist?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // The service first. From Android 12 the microphone may only be opened while the process
        // holds foreground importance, so starting the loop before the service is up gets the
        // recording killed a moment later for no visible reason.
        RecognitionService.start(context)
        engine.start(playlist, continuous.value)
    }

    /** Picked by hand from the candidate list. */
    fun accept(song: SongItem, playlist: Playlist?) {
        engine.add(song)
        // Through the guarded entry point, not straight to the engine, so picking a candidate
        // by hand cannot reopen the microphone on a permission that has since been revoked.
        if (continuous.value) start(playlist) else stop()
    }

    fun setContinuous(value: Boolean) {
        engine.continuous.value = value
    }

    fun stop() {
        engine.stop()
        RecognitionService.stop(context)
    }

    fun reset() {
        engine.reset()
        RecognitionService.stop(context)
    }

    /** Empties the screen's list of recognised songs. Only the screen and Settings ask for this. */
    fun clearRecognised() = engine.clearRecognised()

    /**
     * Separate from [reset], which only empties this session.
     *
     * Clearing what the app remembers hearing is a different act from clearing the current
     * session, and the settings entry that says it clears history should do the one it says.
     */
    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    private companion object {
        const val TAG = "RecognitionViewModel"
    }
}
