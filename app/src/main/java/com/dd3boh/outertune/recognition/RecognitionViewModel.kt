/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One pass of: listen, fingerprint, ask Shazam, then find the song on YouTube.
 *
 * The last step exists because Shazam does not hand back a YouTube id. Verified against a real
 * response rather than assumed: the only YouTube reference in the document is a search url. So a
 * match is a title and an artist, and turning that into something addable means searching, which
 * can land on a live take, a cover or a sped-up edit. That is why this stops at [State.Found] and
 * waits, instead of adding the top hit.
 */
@HiltViewModel
class RecognitionViewModel @Inject constructor(
    private val microphone: MicrophoneListener,
    private val shazam: ShazamClient,
) : ViewModel() {

    /**
     * A song this session put into the playlist, for the running list in the sheet.
     */
    data class Added(val title: String, val artist: String, val auto: Boolean)

    sealed interface State {
        data object Idle : State
        /** [level] is the peak of the last chunk, 0..1, for the meter. */
        data class Listening(val elapsedMs: Long, val level: Float) : State
        data object Identifying : State
        /**
         * Recognised, and waiting for a choice.
         *
         * [certain] is false whenever the top YouTube hit does not clearly correspond to what
         * Shazam named, which is when a human has to look. In continuous mode this state is only
         * ever reached when it is false, because a certain one is added and listening resumes.
         */
        data class Found(
            val track: Recognised,
            val candidates: List<SongItem>,
            val certain: Boolean = false,
        ) : State

        /** Between passes in continuous mode. */
        data class Waiting(val secondsLeft: Int) : State
        data object NoMatch : State
        /** [heardNothing] separates a silent room from Shazam not knowing the song. */
        data class Failed(val reason: String, val heardNothing: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    /** Keep listening after each successful add, instead of closing on the first one. */
    val continuous = MutableStateFlow(false)

    private val _added = MutableStateFlow<List<Added>>(emptyList())
    val added = _added.asStateFlow()

    /** Ids already in the playlist plus ids added this session, so nothing goes in twice. */
    private var known: MutableSet<String> = mutableSetOf()

    private var job: Job? = null
    private var onAutoAdd: ((SongItem) -> Unit)? = null

    /**
     * Told once by the screen, so continuous mode can add without a round trip through the UI and
     * can skip anything the playlist already holds. A song plays for minutes and a pass is twelve
     * seconds, so without this it would recognise and re-add the same track repeatedly.
     */
    fun configure(existingSongIds: Set<String>, onAdd: (SongItem) -> Unit) {
        known = existingSongIds.toMutableSet()
        onAutoAdd = onAdd
    }

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = State.Listening(0, 0f)
            val samples = runCatching {
                microphone.record { elapsed, level ->
                    // Only while still listening: a stop part way through must not have its meter
                    // overwrite the result that follows it.
                    if (_state.value is State.Listening) {
                        _state.value = State.Listening(elapsed, level)
                    }
                }
            }.getOrElse {
                Log.w(TAG, "Recording failed", it)
                _state.value = State.Failed(it.message ?: "microphone", heardNothing = false)
                return@launch
            }

            identify(samples)
        }
    }

    /** Stop early and use what was heard so far, which is what the stop button means. */
    fun stopAndIdentify() {
        val current = _state.value
        if (current !is State.Listening) return
        // Cancelling the recorder returns the samples captured up to that point, so the work
        // continues rather than being thrown away.
        job?.cancel()
        job = viewModelScope.launch { _state.value = State.Identifying }
    }

    private suspend fun identify(samples: ShortArray) {
        _state.value = State.Identifying
        when (val outcome = shazam.identify(samples)) {
            is RecognitionOutcome.Failed -> {
                _state.value = State.Failed(outcome.reason, heardNothing = outcome.reason == "silence")
            }

            RecognitionOutcome.NoMatch -> _state.value = State.NoMatch

            is RecognitionOutcome.Match -> {
                val candidates = YouTube.search(outcome.track.searchQuery, YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()?.items?.filterIsInstance<SongItem>()?.take(4).orEmpty()
                val best = candidates.firstOrNull()
                val certain = best != null && corresponds(outcome.track, best)
                Log.i(
                    TAG,
                    "Matched ${outcome.track.title}, ${candidates.size} candidates, certain=$certain"
                )

                if (continuous.value && certain && best != null) {
                    if (best.id in known) {
                        // Same song still playing, or already in the playlist. Not an error, and
                        // not worth interrupting anybody over.
                        Log.i(TAG, "Already have ${best.title}, listening again")
                        pause()
                        return
                    }
                    known += best.id
                    onAutoAdd?.invoke(best)
                    _added.value += Added(best.title, best.artists.joinToString { it.name }, auto = true)
                    pause()
                    return
                }

                _state.value = State.Found(outcome.track, candidates, certain)
            }
        }
    }

    /**
     * Marks a hand-picked song as taken and, in continuous mode, goes back to listening.
     */
    fun accept(song: SongItem) {
        known += song.id
        _added.value += Added(song.title, song.artists.joinToString { it.name }, auto = false)
        if (continuous.value) pause() else _state.value = State.Idle
    }

    /**
     * A gap between passes.
     *
     * Without it the next pass starts inside the same song and recognises it again, so the whole
     * loop would be spent re-identifying one track. Long enough to make that unlikely, short
     * enough that a three minute song still gets sampled several times.
     */
    private fun pause() {
        job = viewModelScope.launch {
            for (left in GAP_SECONDS downTo 1) {
                _state.value = State.Waiting(left)
                kotlinx.coroutines.delay(1000)
            }
            // Cleared first. start() refuses to run while a job is active, and the active job here
            // is this countdown, so without this the loop stops dead after one pass.
            job = null
            start()
        }
    }

    fun stopContinuous() {
        continuous.value = false
        job?.cancel()
        _state.value = State.Idle
    }

    fun reset() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    override fun onCleared() {
        job?.cancel()
    }

    companion object {
        private const val TAG = "RecognitionViewModel"
        private const val GAP_SECONDS = 8
    }
}
