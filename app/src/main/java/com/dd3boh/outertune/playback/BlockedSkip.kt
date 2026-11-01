/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * Where playback goes from a blocked artist's song that became current (ArtistBlock): the
 * nearest song that is not one, the way the queue was going. Back first for a song reached by
 * going back, so that the previous button keeps going back, and then on; otherwise on only, as a
 * song played before is no answer to one ending. Null when there is no such song, and then the
 * music stops on the blocked one, unheard: there is nothing to go round and round between.
 */
object BlockedSkip {
    /**
     * @param from the place of the blocked song in the queue.
     * @param count how many songs the queue has, the most steps a search takes.
     * @param forward the place after a place, as the player goes (shuffle and repeat all
     *   included), or a negative number at the end.
     * @param backward the place before, the same way.
     * @param blocked whether the song at a place is a blocked artist's.
     * @param back whether the song was reached by going back.
     */
    fun target(
        from: Int,
        count: Int,
        forward: (Int) -> Int,
        backward: (Int) -> Int,
        blocked: (Int) -> Boolean,
        back: Boolean = false,
    ): Int? =
        if (back) find(from, count, backward, blocked) ?: find(from, count, forward, blocked)
        else find(from, count, forward, blocked)

    private fun find(from: Int, count: Int, step: (Int) -> Int, blocked: (Int) -> Boolean): Int? {
        var at = from
        repeat(count) {
            at = step(at)
            // Off the end, or round to where it began (repeat all): nothing left that plays.
            if (at < 0 || at == from) return null
            if (!blocked(at)) return at
        }
        return null
    }
}
