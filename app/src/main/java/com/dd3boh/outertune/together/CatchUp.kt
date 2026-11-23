/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import androidx.media3.common.Player

/**
 * Catch up, and what could leave a guest where it was (dvd in #testers, 22 Nov 2026: "i can catch up but its not catching
 * up"). Catch up sends a guest that went its own way back to where the leader is, and three things undid it:
 *
 *  - Paused on its own, it caught up playing, and the room's queue, swapped in under the song a moment later, put back
 *    the pause it had seen when it was asked for: the guest ended paused here, silent. The swap now leaves the player as
 *    the room wants it ([swapPlays]).
 *  - Its player had stopped on an error while it was on its own, on the room's song (the app stops on a failed song
 *    unless skip on error is on). The room's look leaves a stopped player alone, so it stayed silent under "Following
 *    the host". Catch up starts it again ([restarts]); if the song fails here again, the room keeps it and says why.
 *  - A song change the room had asked for earlier and never got (the player taken elsewhere meanwhile) was still waited
 *    for at every look, which passed the leader's song by. Catch up asks for the leader's song afresh.
 */
internal object CatchUp {
    /** Whether Catch up starts the player again: stopped ([Player.STATE_IDLE]) on [playingId], the room's song. */
    fun restarts(playbackState: Int, playingId: String?, roomSongId: String?): Boolean =
        playbackState == Player.STATE_IDLE && playingId != null && playingId == roomSongId

    /**
     * Whether the player plays once the room's queue is in under the song: not while the listener paused it here, else
     * as the room does ([roomPlaying]), or as it does now ([playingHere]) before the room's first word.
     */
    fun swapPlays(pausedHere: Boolean, roomPlaying: Boolean?, playingHere: Boolean): Boolean =
        !pausedHere && (roomPlaying ?: playingHere)
}

/**
 * How long a guest behind the room waits for the song to come in as far as where the room is (Follow.jumpNow) before it
 * jumps there anyway (dvd in #testers, 22 Nov 2026, on a slow connection: "i can catch up but its not catching up"). It
 * waited for as long as that took, and a stream that comes in no faster than it plays never gets there: a SABR stream
 * with Data Saver on is read about 15 s ahead of the player and no further, a slow line about at the song's own pace. The
 * guest played on behind under "Catching up, N ms apart" for good. Now it waits [LIMIT_MS] at most, longer only while
 * what comes in gains on the room fast enough to get there within [LIMIT_MS] more, then jumps: a short rebuffer. A second
 * wait given up on soon after the first ([AGAIN_WITHIN_MS]) says the jump did not help: the line is too slow to keep up
 * ([tooSlow]), and the guest plays on behind, saying so, until the song has come in that far or another song starts.
 */
internal class DataWait {
    /** The line can't keep up: a jump after a wait given up on did not get the guest to the room. */
    var tooSlow = false
        private set
    private var song: String? = null
    private var since = -1L
    private var lastLook = 0L
    private var shortAtStart = 0L
    private var lastGaveUp = NEVER

    /** The room plays [songId]: another song starts afresh. True when that ends [tooSlow]. */
    fun songIs(songId: String): Boolean {
        if (songId == song) return false
        song = songId
        val was = tooSlow
        reset()
        return was
    }

    /**
     * Whether a guest too far behind to nudge jumps to [targetMs] now, playing at [localMs] with the song come in up to
     * [bufferedMs]: as Follow.jumpNow says, or once the wait for what it lacks has lasted too long. False: it plays on
     * behind meanwhile.
     */
    fun jumps(targetMs: Long, localMs: Long, bufferedMs: Long, nowMs: Long): Boolean {
        if (Follow.jumpNow(targetMs, localMs, bufferedMs)) {
            since = -1L
            tooSlow = false
            return true
        }
        val short = targetMs + Follow.LANDING_MS - bufferedMs
        val fresh = since < 0 || nowMs - lastLook > STALE_MS
        lastLook = nowMs
        if (fresh) {
            since = nowMs
            shortAtStart = short
            return false
        }
        val waited = nowMs - since
        if (tooSlow || waited < LIMIT_MS) return false
        val gained = shortAtStart - short
        // Gaining on the room fast enough to get there within the limit again: still worth the wait, with no rebuffer.
        if (gained > 0 && short * waited <= LIMIT_MS * gained) return false
        since = -1L
        if (nowMs - lastGaveUp <= AGAIN_WITHIN_MS) {
            tooSlow = true
            return false
        }
        lastGaveUp = nowMs
        return true
    }

    /** Afresh, as at another song: Catch up. */
    fun reset() {
        tooSlow = false
        since = -1L
        lastGaveUp = NEVER
    }

    companion object {
        /** The longest wait: as long as a jump is held back after another (SyncLock.SEEK_AGAIN_MS). */
        const val LIMIT_MS = SyncLock.SEEK_AGAIN_MS

        /** A look this long after the last one that waited starts a new wait: the guest was nearer, paused or away. */
        const val STALE_MS = 2_000L

        /** A wait given up on this soon after the last one: the jump did not help, and the line can't keep up. */
        const val AGAIN_WITHIN_MS = 30_000L

        private const val NEVER = Long.MIN_VALUE / 2
    }
}
