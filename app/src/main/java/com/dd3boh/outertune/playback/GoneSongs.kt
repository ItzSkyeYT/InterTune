/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * The songs YouTube called gone and no copy of which played, kept out of the recommendation rows.
 *
 * A song that is gone under its own id and plays from a stand-in is not one of these: its card
 * works ([StandIns]). These are the ones a listener taps and cannot have, and a row that keeps
 * offering them is a row that keeps disappointing. They are written as exclusions of their own
 * kind, for a month, so the Exclusions page lists them with the date and with Lift, and they
 * come back by themselves: a song that plays again under its own id is taken off at once.
 *
 * The three functions are the database's. [load] returns null when it did not answer, and then
 * nothing is known and nothing is lifted, rather than a wait on the thread a song is loading on.
 */
internal class GoneSongs(
    private val load: () -> List<String>?,
    private val mark: (id: String, now: Long) -> Unit,
    private val lift: (ids: List<String>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var known: MutableSet<String>? = null

    private fun known(): MutableSet<String>? = known ?: load()?.toMutableSet()?.also { known = it }

    /** YouTube called [id] gone and no copy of it played. */
    @Synchronized
    fun gone(id: String) {
        // Written even when what is already there could not be read: a second write of the same
        // song changes nothing.
        if (known()?.add(id) != false) mark(id, now())
    }

    /** What was said of [ids] is not believed after all: see [GoneRun]. */
    @Synchronized
    fun doubted(ids: List<String>) {
        val known = known()
        val marked = if (known == null) ids else ids.filter { it in known }
        if (marked.isEmpty()) return
        lift(marked)
        known?.removeAll(marked.toSet())
    }

    /** [id] played under its own id. Nearly always a song nothing was ever said of, and then nothing is done. */
    @Synchronized
    fun plays(id: String) {
        val known = known() ?: return
        if (known.remove(id)) lift(listOf(id))
    }
}
