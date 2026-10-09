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
 * offering them keeps disappointing. They are written as exclusions of their own kind, for a
 * month, so the Exclusions page lists them with the date and with Lift, and they come back by
 * themselves: a song a stream is had for again, its own or a copy's, is taken off at once.
 *
 * Written at the second time, not the first. YouTube can answer "unavailable" for a while for
 * songs that are there (see [GoneRun]), and one such answer must not cost a song a month out of
 * the rows. So a song is written when it is called gone, some other stream is had after that,
 * and it is then called gone again: the app was not being turned away in between, and the song
 * still was. The first time is kept in memory only, so a new process starts the count again.
 *
 * The three functions are the database's. [load] returns the songs written and still in force,
 * or null when it did not answer, and then nothing is lifted on a guess.
 */
internal class GoneSongs(
    private val load: (now: Long) -> List<String>?,
    private val mark: (id: String, now: Long) -> Unit,
    private val lift: (ids: List<String>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The songs written, as far as this knows: read once, then kept up as it writes and lifts. */
    private var written: MutableSet<String>? = null

    /** How many streams have been had, and for each song called gone once, the count at that time. */
    private var streams = 0L
    private val calledGoneAt = HashMap<String, Long>()

    private fun written(): MutableSet<String>? = written ?: load(now())?.toMutableSet()?.also { written = it }

    /** YouTube called [id] gone and no copy of it played. */
    @Synchronized
    fun gone(id: String) {
        val first = calledGoneAt[id]
        if (first == null || first == streams) {
            // The first time, or again with nothing had in between, which says nothing new.
            calledGoneAt[id] = streams
            return
        }
        calledGoneAt.remove(id)
        // The database decides whether there is anything to write: see what [mark] is given.
        mark(id, now())
        written()?.add(id)
    }

    /** A stream was had for [id], under its own id or a stand-in's. */
    @Synchronized
    fun plays(id: String) {
        streams++
        calledGoneAt.remove(id)
        // Nearly always a song nothing was ever written of, and then nothing is done.
        if (written()?.remove(id) == true) lift(listOf(id))
    }
}
