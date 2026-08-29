/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.isInternetConnected
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repairs songs whose stored loudness is missing, so loudness normalisation can level them.
 *
 * WHY THIS EXISTS. Normalisation only ever attenuates, so a track with no stored loudness cannot be
 * levelled at all. Until it is repaired it falls back to a presumed value, which keeps it from
 * blaring but is a guess. This puts the real number back.
 *
 * WHAT IT WILL NOT DO, each of which is a way this could have gone badly wrong:
 *  - It never touches songs that simply have no format row. Those are not broken; a format row is
 *    written with loudness the first time a song is played. Confusing the two would have meant
 *    tens of thousands of requests instead of a few hundred.
 *  - It never writes any column except loudnessDb, via a targeted UPDATE guarded on IS NULL.
 *  - It never repairs the song that is currently playing. After the presumed-loudness fallback an
 *    unrepaired track sits at heavy attenuation, so writing its real value mid-track would make it
 *    jump louder under the listener. It is picked up on the next run instead.
 *  - It never runs without the user asking, and it stops the moment they say stop.
 */
@Singleton
class LoudnessRepair @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Ids that answered "no loudness available" this session.
     *
     * Kept so pressing the button again does not re-ask the same hopeless questions. Deliberately
     * in memory only: a restart is a cheap and obvious way for the user to force a full retry, and
     * it needs no new preference key.
     */
    private val unavailable = mutableSetOf<String>()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    sealed interface State {
        data object Idle : State
        data class Running(val done: Int, val total: Int, val repaired: Int) : State
        data class Finished(
            val repaired: Int,
            val unavailable: Int,
            val failed: Int,
            val stoppedEarly: Boolean,
        ) : State

        data object Offline : State
        data object NothingToDo : State

        /** Too many consecutive failures. Almost always rate limiting, so it stops rather than digs in. */
        data class Blocked(val repaired: Int) : State
    }

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Provides the id that must not be touched, because it is playing right now.
     *
     * A provider rather than a value, so it is read fresh for every song instead of being captured
     * once when the scan starts.
     */
    @Volatile
    var nowPlayingIdProvider: () -> String? = { null }

    fun start() {
        if (isRunning) return
        _state.value = State.Running(done = 0, total = 0, repaired = 0)

        job = scope.launch {
            var repaired = 0
            var unavailableCount = 0
            var failed = 0
            var consecutiveFailures = 0

            try {
                if (!context.isInternetConnected()) {
                    _state.value = State.Offline
                    return@launch
                }

                // Called straight on the database, which delegates to the DAO. Note query {} is
                // fire and forget on an executor and returns Unit, so it cannot be used to read.
                val candidates = database.formatIdsMissingLoudness()
                    .filterNot { it in unavailable }

                if (candidates.isEmpty()) {
                    _state.value = State.NothingToDo
                    return@launch
                }

                Log.i(TAG, "Repairing loudness for ${candidates.size} songs")
                _state.value = State.Running(0, candidates.size, 0)

                candidates.forEachIndexed { index, id ->
                    if (!isActive) return@forEachIndexed

                    // Leave the playing track alone. See the class comment.
                    if (id == nowPlayingIdProvider()) {
                        _state.value = State.Running(index + 1, candidates.size, repaired)
                        return@forEachIndexed
                    }

                    when (val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        YTPlayerUtils.loudnessFor(id)
                    }) {
                        is YTPlayerUtils.LoudnessResult.Found -> {
                            consecutiveFailures = 0
                            if (database.fillMissingLoudness(id, result.loudnessDb) > 0) repaired++
                        }

                        is YTPlayerUtils.LoudnessResult.Unavailable -> {
                            consecutiveFailures = 0
                            unavailable += id
                            unavailableCount++
                        }

                        // Includes the null case, which is the timeout.
                        else -> {
                            failed++
                            consecutiveFailures++
                            if (consecutiveFailures >= FAILURE_ABORT_THRESHOLD) {
                                Log.w(TAG, "Stopping: $consecutiveFailures failures in a row")
                                _state.value = State.Blocked(repaired)
                                return@launch
                            }
                        }
                    }

                    _state.value = State.Running(index + 1, candidates.size, repaired)

                    // Paced on purpose. These are sequential anonymous requests against YouTube's
                    // player endpoint and the point is to be unremarkable, not fast. A few hundred
                    // songs at this rate is about a minute.
                    if (index < candidates.lastIndex) delay(REQUEST_GAP_MS)
                }

                _state.value = State.Finished(
                    repaired = repaired,
                    unavailable = unavailableCount,
                    failed = failed,
                    stoppedEarly = !isActive,
                )
                Log.i(TAG, "Loudness repair done: $repaired repaired, $unavailableCount unavailable, $failed failed")
            } catch (e: Exception) {
                // Cancellation lands here too; report what was achieved rather than losing it.
                Log.w(TAG, "Loudness repair ended early", e)
                _state.value = State.Finished(repaired, unavailableCount, failed, stoppedEarly = true)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Clears a finished result so the settings row returns to its resting state. */
    fun acknowledge() {
        if (!isRunning) _state.value = State.Idle
    }

    companion object {
        private const val TAG = "LoudnessRepair"

        /**
         * Gap between requests, in milliseconds.
         *
         * Not tuned for speed. 355 songs at this rate is roughly 2 minutes, which is a reasonable
         * price for never being the reason an account gets rate limited.
         */
        private const val REQUEST_GAP_MS = 350L

        private const val REQUEST_TIMEOUT_MS = 15_000L

        /**
         * Consecutive failures before giving up entirely.
         *
         * A handful of songs failing is normal, they may be region locked or withdrawn. A long
         * unbroken run of failures means something systemic, most likely rate limiting, and
         * hammering it hundreds more times is the worst possible response.
         */
        private const val FAILURE_ABORT_THRESHOLD = 8
    }
}
