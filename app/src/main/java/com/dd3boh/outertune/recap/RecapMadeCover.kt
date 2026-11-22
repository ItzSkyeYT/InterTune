/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/**
 * A song's cover in the Recap when it may have none: a file on the phone, or a song with no address
 * for a picture (RecapReader). Its own picture first, then its folder's (the file, then LocalCovers,
 * as CoilBitmapLoader goes), and only then one the Recap makes ([MadeCoverPlan], drawn by
 * RecapCoverFetcher), so that a library with no art is not the same slate square on every slide.
 * His pick for listeners of their own files, 22 Nov 2026, from the sheet local-covers-sheet.jpg.
 * [path] is the file's, null for a song that is not one.
 */
data class RecapMadeCover(val title: String, val artists: String, val path: String?)

/** How a made cover looks, from the song's title alone, so that a song has the same cover every time. */
object MadeCoverPlan {
    /** Under this many pixels across, the title's first letter alone: a whole title cannot be read so small. */
    const val SMALL_PX = 160

    /** The title in this many lines at most. */
    const val MAX_LINES = 3

    enum class Shape { CIRCLE, PILL, TRIANGLE }

    private fun seed(title: String) = title.trim().hashCode() and Int.MAX_VALUE

    /** Which of [count] papers [title] is drawn on. */
    fun paper(title: String, count: Int): Int = seed(title) % count

    /** The paper shape in the cover's corner. */
    fun shape(title: String): Shape = Shape.entries[(seed(title) / 7) % Shape.entries.size]

    /** The first letter or digit of [title], as a capital; a note where it has none. */
    fun letter(title: String): String = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"

    /**
     * [title] in at most [MAX_LINES] lines no wider than [width] by [measure], its words kept whole;
     * null where it does not fit, so that the caller tries a smaller size.
     */
    fun lines(title: String, width: Float, measure: (String) -> Float): List<String>? {
        val words = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = ArrayList<String>()
        var line = ""
        for (word in words) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (measure(trial) <= width) {
                line = trial
            } else {
                if (line.isEmpty() || measure(word) > width) return null
                out += line
                line = word
            }
        }
        out += line
        return out.takeIf { it.size <= MAX_LINES }
    }
}
