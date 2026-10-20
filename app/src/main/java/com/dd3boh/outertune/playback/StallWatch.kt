/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.utils.RouteTimes

/**
 * Silence at the speaker: the player buffering, wanting to play, and no byte of the song coming
 * in. Decided here without Android so that it is tested; MusicService asks it once a second, and
 * only while the player buffers and wants to play.
 *
 * Media3 waits on a connection until its read gives up, then retries the same address until the
 * buffer is empty. A connection that never answers, or a host that keeps hanging, held the song
 * silent for a minute on 20 Oct 2026. So after [stallMs] with no byte the connection is cut (the
 * player asks again at once, on a new one, from the same byte); after as long again the route is
 * changed; and after as long again the song is given up with a message. Any byte that comes in
 * starts the count again, so a slow stream that still comes in is never cut.
 */
class StallWatch(private val stallMs: Long = RouteTimes.STALL_MS) {
    /** What the service knows of the player at one moment. */
    data class Tick(
        val songId: String?,
        val wantsToPlay: Boolean,
        val buffering: Boolean,
        /** The song was resolved to a stream, not a local file, a download or the cache. */
        val streamed: Boolean,
        /** A resolve of the song is out: it has its own deadline. */
        val resolving: Boolean,
        val casting: Boolean,
        val waitingForNetwork: Boolean,
        val online: Boolean,
        /** Bytes the player's stream reads have brought in, all songs together. */
        val bytes: Long,
        val now: Long,
        /** The remote switch: see RemoteRoutes. */
        val allowed: Boolean = true,
    )

    enum class Action { NOTHING, CUT_CONNECTION, CHANGE_ROUTE, GIVE_UP }

    private var song: String? = null
    private var lastBytes = -1L
    private var lastTickAt: Long? = null

    /** When the silence began, for the log. */
    private var silentSince: Long? = null

    /** When the current step began: the next one comes [stallMs] after it. */
    private var stepSince: Long? = null
    private var stage = 0

    /** How long the song has been silent, for the log. */
    fun silentMs(now: Long): Long = silentSince?.let { now - it } ?: 0

    private fun watchedApartFromResolving(t: Tick) =
        t.allowed && t.songId != null && t.wantsToPlay && t.buffering && t.streamed &&
            !t.casting && !t.waitingForNetwork && t.online

    /** Forgets the silence counted so far: the watch is being started again. */
    fun reset() {
        lastTickAt = null
        lastBytes = -1L
        startAgain()
    }

    private fun startAgain() {
        silentSince = null
        stepSince = null
        stage = 0
    }

    fun tick(t: Tick): Action {
        // Ticks stop while the player is paused or stopped on an error. A count kept across that
        // gap gave a song up a few seconds after play was pressed again, or right after its quiet
        // retry, on a silence long over: a watch started again counts from then.
        val gap = lastTickAt?.let { t.now - it > GAP_MS } ?: false
        lastTickAt = t.now
        if (t.songId != song || gap) {
            song = t.songId
            startAgain()
        }
        if (!watchedApartFromResolving(t) || t.bytes != lastBytes) {
            lastBytes = t.bytes
            startAgain()
            if (watchedApartFromResolving(t) && !t.resolving) {
                silentSince = t.now
                stepSince = t.now
            }
            return Action.NOTHING
        }
        if (t.resolving) {
            // A new route being asked for, which has its own deadline: the clock stops and the
            // step reached is kept. Starting again from nothing after every route change put the
            // give-up at 40 to 55 s instead of 18.
            stepSince = null
            return Action.NOTHING
        }
        if (silentSince == null) silentSince = t.now
        val since = stepSince ?: t.now.also { stepSince = it }
        if (stage >= 3 || t.now - since < stallMs) return Action.NOTHING
        stage++
        stepSince = t.now
        return when (stage) {
            1 -> Action.CUT_CONNECTION
            2 -> Action.CHANGE_ROUTE
            else -> Action.GIVE_UP
        }
    }

    companion object {
        /** Ticks come once a second; a longer gap means the watch was stopped meanwhile. */
        const val GAP_MS = 2_500L
    }
}
