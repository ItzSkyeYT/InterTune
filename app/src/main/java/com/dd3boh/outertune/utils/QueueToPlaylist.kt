/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * The decisions behind saving the queue sheet as a playlist, kept away from Android so they can be tested.
 *
 * The queue sheet already knows which songs it is showing and in what order; what it does not know is what
 * to call the playlist or how to number the songs. The name comes from the queue's title, which is often a
 * radio or an album name that the user may already have a playlist for, so a taken name gets a counter the
 * way a file manager would do it rather than silently making a second playlist with the same name.
 */
object QueueToPlaylist {
    /** What a queue with no usable title is called; the sheet passes the translated word instead. */
    const val FALLBACK_NAME = "Queue"

    /**
     * A name for the playlist: [queueTitle] trimmed, or [fallback] when there is nothing to trim, with
     * " (2)", " (3)" and so on appended until it no longer matches one of [existing], ignoring case.
     * A gap in the numbering is reused, so "Queue", "Queue (2)" and "Queue (4)" give "Queue (3)".
     */
    fun defaultName(queueTitle: String?, existing: Collection<String>, fallback: String = FALLBACK_NAME): String {
        val base = queueTitle?.trim().orEmpty().ifEmpty { fallback.trim().ifEmpty { FALLBACK_NAME } }
        val taken = existing.mapTo(HashSet()) { it.trim().lowercase() }
        if (base.lowercase() !in taken) return base
        var number = 2
        while ("$base ($number)".lowercase() in taken) number++
        return "$base ($number)"
    }

    /**
     * Each song id with the position it will hold in the playlist, in the order given. A queue may hold
     * the same song twice and the playlist should too, so duplicates are kept where they are.
     */
    fun positions(songIds: List<String>): List<Pair<String, Int>> =
        songIds.mapIndexed { index, id -> id to index }
}
