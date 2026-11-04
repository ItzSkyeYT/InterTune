/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * Where Add to queue puts songs (his note of 30 Oct 2026: "When adding something to the end of
 * the queue, in auto queue, where does it go?").
 *
 * In a list somebody chose, a playlist, an album or a queue made by hand, at its end, as always.
 * A radio has no end worth the name: it holds forty or fifty songs from the start and tops itself
 * up five songs before it runs out, and with Keep radios up to date on, everything past the next
 * few songs is folded out of sight. A song added there went in behind every song the radio held,
 * two or three hours away, where the queue did not show it. The menu's Add to queue was worse: it
 * also took the radio's continuation away, so the radio stopped topping itself up and stopped
 * keeping up to date.
 *
 * So in a radio the songs go after the listener's own songs that follow the song playing (put in
 * with Play next or Add to queue, or moved there by hand) and before the radio's own, as Spotify's
 * "Next in queue" does, and the radio carries on after them. No Android in here, so it is tested.
 */
internal object AddToQueue {

    /**
     * The place in play order for songs added to a queue holding [ids] in play order, with its
     * song playing at [playing]: the end of a list somebody chose; in a [radio], the first place
     * after [playing] whose song is not one of the listener's own, [byHand].
     */
    fun place(ids: List<String>, playing: Int, radio: Boolean, byHand: Set<String>): Int {
        if (!radio) return ids.size
        var at = (playing + 1).coerceIn(0, ids.size)
        while (at < ids.size && ids[at] in byHand) at++
        return at
    }
}
