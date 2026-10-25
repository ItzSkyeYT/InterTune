/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.recap.RecapReader
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recognition.RecognitionHistory
import com.dd3boh.outertune.utils.PollChecker
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * the 31st, and its count. See RecapReader and Recaps.
 */
@HiltViewModel
class RecapViewModel @Inject constructor(
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

    private companion object {
        const val TAG = "Recap"

        /** How long a Recap worked out is shown again as it is. */
        const val FRESH_MS = 60_000L
    }
}
