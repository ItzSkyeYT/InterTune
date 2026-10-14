/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

/**
 * Copying and sharing lyrics from the lyrics view: all of them as text, or a few lines picked for a
 * picture (LyricsCard). What goes out is the text as the view shows it, line by line, without the
 * timings a synced file carries. No Android in here, so it is tested.
 */
object LyricsShare {
    /**
     * The most lines a picture takes. A card is read at a glance on somebody else's phone, and past
     * five lines the text has to get small enough that it is not.
     */
    const val MOST_LINES = 5

    /**
     * The lyrics as text, from the view's lines: each line without the spaces after it, one empty
     * line where the view has a gap between verses however many it has there, and none at the
     * start or the end.
     */
    fun text(lines: List<String>): String {
        val out = ArrayList<String>(lines.size)
        for (line in lines.map { it.trimEnd() }) {
            if (line.isBlank() && (out.isEmpty() || out.last().isEmpty())) continue
            out += line
        }
        while (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }

    /** What Share sends: the song's title and its artists over the lyrics, so that they say whose they are. */
    fun message(title: String, artists: String, lines: List<String>): String = buildString {
        append(title.trim())
        if (artists.isNotBlank()) append('\n').append(artists.trim())
        append("\n\n").append(text(lines))
    }

    /** What a tap on a line does while lines are being picked. */
    sealed interface Pick {
        /** The lines picked now, by their place in the lyrics, in the order they were picked. */
        data class Picked(val lines: List<Int>) : Pick

        /** [MOST_LINES] are picked already, and the tap was on another. */
        data object Full : Pick

        /** The line is empty: a gap between verses, or a pause in a synced file. Nothing to share. */
        data object Empty : Pick
    }

    /** A tap on the line at [index] of [lines], with [picked] picked so far: picked if it was not, put back if it was. */
    fun toggle(picked: List<Int>, index: Int, lines: List<String>): Pick = when {
        index in picked -> Pick.Picked(picked - index)
        lines.getOrNull(index).isNullOrBlank() -> Pick.Empty
        picked.size >= MOST_LINES -> Pick.Full
        else -> Pick.Picked(picked + index)
    }

    /** The picked lines for the picture, in the order the song has them, whatever order they were picked in. */
    fun pickedLines(lines: List<String>, picked: Collection<Int>): List<String> =
        picked.distinct().sorted().mapNotNull { lines.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }

    /**
     * A name for the picture's file, which some apps show beside it: the song's title, with
     * nothing a file name could choke on, or "lyrics" when nothing is left of it.
     */
    fun fileName(title: String): String {
        val kept = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim()
        return (kept.ifEmpty { "lyrics" }) + ".png"
    }
}
