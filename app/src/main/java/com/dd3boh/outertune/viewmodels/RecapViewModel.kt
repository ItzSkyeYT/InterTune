/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecapPlaylistOnYouTubeMusicKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.recap.RecapPlayer
import com.dd3boh.outertune.recap.RecapPlaylist
import com.dd3boh.outertune.recap.RecapReader
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSound
import com.dd3boh.outertune.recognition.RecognitionHistory
import com.dd3boh.outertune.ui.screens.recap.RecapCards
import com.dd3boh.outertune.utils.PollChecker
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where working out a Recap has got to. */
sealed interface RecapState {
    data object Working : RecapState

    /** The year holds too little for a Recap, or the read failed. */
    data object Nothing : RecapState

    data class Ready(val shown: RecapShown) : RecapState
}

/**
 * The Recap's numbers, worked out afresh each time it is opened, since the year goes on until
 * the 31st, its count, and its sound. See RecapReader and Recaps.
 */
@HiltViewModel
class RecapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val history: RecognitionHistory,
    private val pollChecker: PollChecker,
) : ViewModel() {
    private val _state = MutableStateFlow<RecapState>(RecapState.Working)
    val state: StateFlow<RecapState> = _state.asStateFlow()

    private var reading: Job? = null
    private var readYear = 0
    private var readAt = 0L

    /**
     * Works the year out again, unless it was a moment ago: the window drawn again after the phone
     * is turned asks for it too, and would flash the loading screen over the slide it was on.
     */
    fun load(year: Int) {
        val fresh = SystemClock.elapsedRealtime() - readAt < FRESH_MS
        if (year == readYear && fresh && (_state.value is RecapState.Ready || reading?.isActive == true)) return
        reading?.cancel()
        readYear = year
        readAt = SystemClock.elapsedRealtime()
        _state.value = RecapState.Working
        reading = viewModelScope.launch {
            _state.value = try {
                val started = System.nanoTime()
                RecapReader.read(database, history, year)
                    ?.also { Log.d(TAG, "Recap $year: ${it.recap.plays} plays, ${(System.nanoTime() - started) / 1_000_000} ms") }
                    ?.let { RecapState.Ready(it) }
                    ?: RecapState.Nothing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                RecapState.Nothing
            }
        }
    }

    /** The story was opened: counted once per install and year, a bare number (PollChecker.recapOpened). */
    fun opened(year: Int) {
        viewModelScope.launch { pollChecker.recapOpened(year) }
    }

    /**
     * The sound of the Recap open now (RecapSound), kept here so that the window drawn again after
     * a change of settings carries on with it; silent without the app's player to play it with.
     */
    private var sound: RecapPlayer? = null

    fun sound(service: MusicService?): RecapSound {
        sound?.let { return it }
        if (service == null) return RecapSound.Silent
        return RecapPlayer(context, service, viewModelScope).also { sound = it }
    }

    /** The Recap was closed: its sound decides what becomes of the listener's music (RecapSound.close). */
    fun closeSound() {
        sound?.close()
        sound = null
    }

    /**
     * The year card's Save as a playlist: the year's most played songs as a playlist on this phone,
     * made or, if an earlier tap made it, updated (RecapPlaylist.save). Null where it did not work.
     */
    suspend fun savePlaylist(shown: RecapShown, name: String): RecapPlaylist.Saved? = withContext(Dispatchers.IO) {
        try {
            RecapPlaylist.save(
                RecapPlaylist.roomStore(database), shown.recap.year, name, shown.recap.ranking,
                cover = RecapCards.saveCover(context, shown.recap.year),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    /** Years whose playlist is being put on YouTube Music now, so that a window drawn again does not start a second copy. */
    private val copying = HashSet<Int>()

    /** Whether the playlist of [year] is on YouTube Music from this phone, or going there now: it is not offered again. */
    suspend fun onYouTubeMusic(year: Int): Boolean =
        year in copying || year.toString() in context.dataStore.data.first()[RecapPlaylistOnYouTubeMusicKey].orEmpty()

    /**
     * The year card's second button: the playlist [saved] copied to YouTube Music (RecapPlaylist.putOnYouTubeMusic).
     * It runs on this view model's scope and not the window's, so closing the Recap does not leave half a playlist
     * in the account. [progress] is told how far it has got, from any thread; [done] is told how it ended, on the
     * main thread, after a failure has been said with a toast. A copy that went is written down, so that it is not offered again.
     */
    fun putOnYouTubeMusic(
        year: Int,
        name: String,
        saved: RecapPlaylist.Saved,
        progress: (done: Int, of: Int) -> Unit,
        done: (RecapPlaylist.Pushed) -> Unit,
    ) {
        if (!copying.add(year)) {
            // One going already (the window was drawn again): not a second.
            done(RecapPlaylist.Pushed.Failed)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                RecapPlaylist.putOnYouTubeMusic(context, name, saved.songs, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                RecapPlaylist.Pushed.Failed
            }
            if (result is RecapPlaylist.Pushed.Done) {
                context.dataStore.edit { it[RecapPlaylistOnYouTubeMusicKey] = it[RecapPlaylistOnYouTubeMusicKey].orEmpty() + year.toString() }
            }
            withContext(Dispatchers.Main) {
                copying.remove(year)
                if (result is RecapPlaylist.Pushed.Failed) say(R.string.create_sync_playlist_failed)
                done(result)
            }
        }
    }

    /** A short word to the listener, from wherever it is said: a toast. */
    fun say(@StringRes text: Int) {
        viewModelScope.launch(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    /** The window went for good with the Recap open: its players let go, the listener's music left as it is. */
    override fun onCleared() {
        sound?.release()
        sound = null
    }

    private companion object {
        const val TAG = "Recap"

        /** How long a Recap worked out is shown again as it is. */
        const val FRESH_MS = 60_000L
    }
}
