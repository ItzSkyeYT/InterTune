/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/**
 * Follows one song on a DLNA renderer from what polling says, since a renderer tells a sender
 * nothing unless asked (GENA events would, at the price of a callback server on the phone;
 * design.md section 2.2).
 *
 * What a poll says is turned into a [ReceiverState] by these rules:
 * - PLAYING, PAUSED_PLAYBACK and TRANSITIONING mean what they say;
 * - STOPPED (or NO_MEDIA_PRESENT) after the song has played is its end when the position last
 *   heard, carried on to now, is within [endSlackMs] of the length, and otherwise a stop made on
 *   the device itself;
 * - PLAYING back at the start when the song had got within [endSlackMs] of its end is doubted
 *   once: a poll asks two questions, and when the song ends between them the renderer says it is
 *   playing and then that it is at 0, which taken as it stands would make the stop that follows
 *   look like one made on the device, and the queue would stop there;
 * - STOPPED before the song has ever played is the renderer still opening it, until
 *   [startGraceMs] after Play, when it is taken as a failure: renderers that cannot fetch or
 *   decode a url mostly just stop, without an error;
 * - a song loaded paused sits STOPPED, which is shown as paused at its start;
 * - a TransportStatus of ERROR_OCCURRED fails the song, unless it is playing regardless.
 *
 * A start past 0, or a seek asked before the renderer plays, waits in [pendingSeekMs]: most
 * renderers refuse Seek while STOPPED.
 */
class DlnaTracker(
    startMs: Long,
    durationHintMs: Long,
    private val deviceName: String,
    play: Boolean,
    private val startGraceMs: Long = 8_000,
    private val endSlackMs: Long = 5_000,
) {
    data class View(val state: ReceiverState, val positionMs: Long, val durationMs: Long, val error: String? = null)

    /** Where to seek once the renderer allows it, or -1. */
    var pendingSeekMs: Long = if (startMs > 0) startMs else -1
        private set

    private var playAskedAt = -1L
    private var stopAsked = false
    private var sawPlaying = false
    private var lastPlayingMs = -1L
    private var lastPlayingAt = 0L

    /** Whether the renderer was playing at the last report, so the position was moving since. */
    private var moving = false

    /** Whether the last report was a position back at the start that was not believed. */
    private var doubted = false

    private var durationMs = durationHintMs

    var view = View(if (play) ReceiverState.LOADING else ReceiverState.PAUSED, startMs.coerceAtLeast(0), durationHintMs)
        private set

    val isFinished: Boolean get() = view.state == ReceiverState.ENDED || view.state == ReceiverState.FAILED

    fun playAsked(at: Long) {
        if (playAskedAt < 0 || !sawPlaying) playAskedAt = at
        stopAsked = false
        if (!isFinished && view.state != ReceiverState.PLAYING) view = view.copy(state = ReceiverState.LOADING)
    }

    fun stopAsked() {
        stopAsked = true
        if (!isFinished) view = view.copy(state = ReceiverState.IDLE, positionMs = 0)
    }

    /** A seek the user asked for. Sent at once when the renderer is playing or paused, else kept. */
    fun seekAsked(positionMs: Long): Boolean {
        val now = view.state == ReceiverState.PLAYING || (view.state == ReceiverState.PAUSED && sawPlaying)
        pendingSeekMs = if (now) -1 else positionMs.coerceAtLeast(0)
        if (!isFinished) view = view.copy(positionMs = positionMs.coerceAtLeast(0))
        lastPlayingMs = positionMs.coerceAtLeast(0)
        moving = false
        return now
    }

    /** The pending seek, once the renderer can take it; the caller sends it. */
    fun takePendingSeek(): Long? {
        if (pendingSeekMs < 0) return null
        if (view.state != ReceiverState.PLAYING && !(view.state == ReceiverState.PAUSED && sawPlaying)) return null
        return pendingSeekMs.also { pendingSeekMs = -1 }
    }

    /**
     * One poll's answer: GetTransportInfo's state and status, GetPositionInfo's RelTime and
     * TrackDuration (null when missing or NOT_IMPLEMENTED), heard at [at] on the link's clock.
     */
    fun report(transportState: String?, transportStatus: String?, relTimeMs: Long?, trackDurationMs: Long?, at: Long): View {
        if (isFinished) return view
        trackDurationMs?.takeIf { it > 0 }?.let { durationMs = it }
        val state = transportState?.trim()?.uppercase()
        val failedStatus = transportStatus?.trim()?.equals("ERROR_OCCURRED", ignoreCase = true) == true
        val waiting = pendingSeekMs.takeIf { it >= 0 }

        if (failedStatus && state != "PLAYING") {
            view = View(ReceiverState.FAILED, view.positionMs, durationMs, "$deviceName could not play it")
            return view
        }
        view = when (state) {
            "PLAYING" -> {
                sawPlaying = true
                val reached = carriedOn(at)
                if (!doubted && moving && relTimeMs != null && isBackAtStart(relTimeMs, reached)) {
                    // Carried on from before instead; the next poll says whether the song ended
                    // (stopped) or really began again (playing from near the start).
                    doubted = true
                    View(ReceiverState.PLAYING, waiting ?: reached.coerceAtMost(durationMs), durationMs)
                } else {
                    doubted = false
                    val position = relTimeMs ?: reached
                    lastPlayingMs = position
                    lastPlayingAt = at
                    moving = true
                    View(ReceiverState.PLAYING, waiting ?: position, durationMs)
                }
            }
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> {
                val position = relTimeMs ?: view.positionMs
                if (sawPlaying || playAskedAt >= 0) {
                    lastPlayingMs = position
                    lastPlayingAt = at
                }
                moving = false
                View(ReceiverState.PAUSED, waiting ?: position, durationMs)
            }
            "TRANSITIONING" -> View(ReceiverState.LOADING, waiting ?: relTimeMs ?: view.positionMs, durationMs)
            "STOPPED", "NO_MEDIA_PRESENT" -> stopped(at).also {
                // Stopped is still: a second poll that finds it stopped must find it where it was.
                if (moving) lastPlayingMs = it.positionMs
                moving = false
            }
            // Anything else is a renderer's own word; nothing is concluded from it.
            else -> view.copy(durationMs = durationMs)
        }
        return view
    }

    private fun stopped(at: Long): View = when {
        stopAsked -> View(ReceiverState.IDLE, 0, durationMs)
        sawPlaying -> {
            val reached = carriedOn(at)
            if (durationMs <= 0 || reached >= durationMs - endSlackMs) {
                View(ReceiverState.ENDED, if (durationMs > 0) durationMs else reached, durationMs)
            } else {
                View(ReceiverState.IDLE, reached, durationMs, "Stopped on $deviceName")
            }
        }
        playAskedAt < 0 -> View(ReceiverState.PAUSED, view.positionMs, durationMs)
        at - playAskedAt >= startGraceMs -> View(ReceiverState.FAILED, view.positionMs, durationMs, "$deviceName stopped without playing it")
        else -> View(ReceiverState.LOADING, view.positionMs, durationMs)
    }

    /**
     * Whether [heardMs] is the renderer back at the start when the song had [reachedMs] of its
     * length behind it, within [endSlackMs] of the end. Only a jump back: a short song moving on
     * normally is near its start and its end at once.
     */
    private fun isBackAtStart(heardMs: Long, reachedMs: Long): Boolean =
        durationMs > 0 && reachedMs >= durationMs - endSlackMs && heardMs < endSlackMs && heardMs + endSlackMs < reachedMs

    /** The last position heard, moved on to [at] if the renderer was playing then. */
    private fun carriedOn(at: Long): Long = when {
        lastPlayingMs < 0 -> view.positionMs
        moving -> lastPlayingMs + (at - lastPlayingAt).coerceAtLeast(0)
        else -> lastPlayingMs
    }
}
