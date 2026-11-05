/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.utils.YTPlayerUtils

/**
 * A song YouTube calls gone is passed over to the next one, whatever "Auto skip to next song when
 * error occurs" says, and a line names it (issue #82). The phone fetched the next song ahead,
 * every client called it "not available", and when the song before it ended, the screen off, the
 * music stopped on it until the next one was started by hand. The setting was off, as it is by
 * default, and that song was never going to play.
 *
 * Only on YouTube's own word, every client's, that the song is gone
 * ([YTPlayerUtils.SongUnavailable]), and only before any of it played in this play. A song
 * YouTube served a moment ago and calls gone partway through is more likely the connection turned
 * away for a while (issue #28) than a song taken down mid-play, and keeps the setting. So do a
 * refusal, a timeout, the bot check and the age gate, which a retry or another route can mend,
 * and a run of songs called gone that StandIns no longer believes ([GoneRun]): that one arrives
 * as [YTPlayerUtils.ConnectionTurnedAway], never as gone.
 *
 * Where to: the next song, or for a song reached by going back the one before it, so that Previous
 * keeps going back past it, as [BlockedSkip] does. With nowhere to go, or when one more error would
 * be too many in a row (the runaway guard of skip on error), the music stops on it as it always did.
 */
object GoneSkip {
    /** Whether [error] is YouTube calling the song gone, every client, and not the connection turned away. */
    fun isGone(error: Throwable?): Boolean =
        YTPlayerUtils.isUnavailableSong(error) && !YTPlayerUtils.isConnectionTurnedAway(error)

    /**
     * The place to go on to from the song the player stopped on with [error], or null to stop or
     * skip as the setting says.
     *
     * @param failed the song the error names, and [current] the one the player is on: the same, or nothing is done.
     * @param readyFor the song the player was last ready to play since the last change of song:
     *   [failed] means some of it played in this play.
     * @param at the failed song's place in the queue; [next] and [previous] the places after and
     *   before it as the player goes (shuffle and repeat included), negative for none.
     * @param back whether the song was reached by going back.
     * @param tooMany whether one more error now would be too many in a row.
     */
    fun target(
        error: Throwable?,
        failed: String?,
        current: String?,
        readyFor: String?,
        at: Int,
        next: Int,
        previous: Int,
        back: Boolean,
        tooMany: Boolean,
    ): Int? {
        if (failed == null || failed != current || readyFor == failed || tooMany || !isGone(error)) return null
        val ways = if (back) listOf(previous, next) else listOf(next)
        // Repeat all round a queue of one comes back to the same song: nowhere to go.
        return ways.firstOrNull { it >= 0 && it != at }
    }
}
