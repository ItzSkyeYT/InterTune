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

    sealed interface State {
        data object Idle : State
        /** [level] is the peak of the last chunk, 0..1, for the meter. */
        data class Listening(val elapsedMs: Long, val level: Float) : State
        data object Identifying : State
        data class Found(val track: Recognised, val candidates: List<SongItem>) : State
        data object NoMatch : State
        /** [heardNothing] separates a silent room from Shazam not knowing the song. */
        data class Failed(val reason: String, val heardNothing: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private var job: Job? = null

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
                Log.i(TAG, "Matched ${outcome.track.title}, ${candidates.size} candidates on YouTube")
                _state.value = State.Found(outcome.track, candidates)
            }
        }
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
    }
}
