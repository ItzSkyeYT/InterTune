/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

/**
 * Undo for songs taken out of the queue by hand (the owner's pick of 11 Nov 2026, after testers found the
 * selection pill's Remove from queue too easy to hit): a swipe, the pill's button and the menu's entry all
 * leave a small message with Undo for a few seconds, and Undo puts the songs back where they were, in
 * order. Where they go back is worked out here, on plain places, so that a test can hold every case.
 *
 * The places are in play order, as the rows of the sheet are and as the queue and the player number
 * theirs, and each is the song's place at the moment it was taken. A set taken together is taken from the
 * last place to the first (the places still to go keep their songs), so each place is the song's own in the
 * queue as it was before any of them went.
 */
internal object QueueUndo {
    /** A song taken out of the queue, and the place it had in play order. */
    data class Taken<T>(val place: Int, val song: T)

    /**
     * What to put back, in the order to put it, each with the place to put it at: from the first place to
     * the last, so that every song goes in after all the ones that stood before it, which are in by then,
     * and none has to be moved for another. [sizeNow] is how many songs the queue holds before the first goes
     * in. A queue that has grown or shrunk meanwhile (a radio topping it up, a song dragged, one more
     * removed) puts each at its place as far as that place still is: a place past the end goes at the end,
     * in the order the songs were taken.
     */
    fun <T> insertions(taken: List<Taken<T>>, sizeNow: Int): List<Taken<T>> {
        var size = sizeNow
        return taken.sortedBy { it.place }.map { Taken(it.place.coerceIn(0, size), it.song).also { size++ } }
    }

    /** [list] with [taken] put back where they were, in order: what the queue holds after Undo. */
    fun <T> putBack(list: List<T>, taken: List<Taken<T>>): List<T> {
        val out = list.toMutableList()
        for (at in insertions(taken, out.size)) out.add(at.place, at.song)
        return out
    }
}
