/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The change being edited, handed from the Mix editor to playback as each edit is made (his ask of
 * 9 Nov 2026: "as soon as a change is made it should be hearable by the player"), in the one
 * process both live in. Playback takes it in place of what is saved for the pair, which the
 * editor writes a moment after the last edit: a change still to come is planned from it, and one
 * planned already and not yet heard is planned again from it. Kept while the editor is open; on
 * leaving, what it saved is what playback reads again.
 */
object MixLive {

    /** An edit: the change as it would be saved, or null where it would be Auto's (the pair forgotten). */
    class Edit(val spec: TransitionSpec?, val atNanos: Long)

    private val edits = ConcurrentHashMap<SongPair, Edit>()
    private val listeners = CopyOnWriteArrayList<(SongPair) -> Unit>()

    /** The editor's change for [pair] as it is now; [spec] null for Auto's. Listeners hear of it at once, on the caller's thread. */
    fun edited(pair: SongPair, spec: TransitionSpec?) {
        val last = edits[pair]
        if (last != null && last.spec == spec) return
        edits[pair] = Edit(spec, System.nanoTime())
        for (l in listeners) l(pair)
    }

    /** The edit for [pair] while its editor is open, or null: read the store then. */
    operator fun get(pair: SongPair): Edit? = edits[pair]

    /** The editor for [pair] is gone, and what it saved is in the store. */
    fun closed(pair: SongPair) {
        edits.remove(pair)
    }

    fun listen(listener: (SongPair) -> Unit) {
        listeners += listener
    }

    fun stopListening(listener: (SongPair) -> Unit) {
        listeners -= listener
    }
}
