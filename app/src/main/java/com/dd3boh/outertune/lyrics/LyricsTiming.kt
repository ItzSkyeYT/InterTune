/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Synced lyrics moved earlier or later against their song, per song (his ask of 18 Oct 2026, for
 * 0.13: "a slider that lets me change the delay between the audio track and lyrics track").
 *
 * The shift is how much later than their own timestamps the lines come: a negative shift brings
 * the lyrics earlier, for lyrics that run behind the singing. Kept as "songId=ms" pairs joined by
 * commas in one setting ([com.dd3boh.outertune.constants.LyricsTimingKey]), only for the songs
 * somebody moved.
 */
object LyricsTiming {
    /** As far as the slider goes either way: the furthest off a lyrics source is seen to be. */
    const val MOST_MS = 5_000L

    /** One press of the fine buttons, and the slider's own step. */
    const val STEP_MS = 100L

    /** The song's shift in [raw], 0 for a song never moved or a null [songId]. */
    fun shiftOf(raw: String, songId: String?): Long {
        if (songId == null || raw.isEmpty()) return 0L
        return parse(raw)[songId] ?: 0L
    }

    /** [raw] with [songId] moved by [shiftMs], kept to the slider's range and steps; 0 forgets it. */
    fun withShift(raw: String, songId: String, shiftMs: Long): String {
        val all = parse(raw).toMutableMap()
        val kept = snap(shiftMs)
        if (kept == 0L) all.remove(songId) else all[songId] = kept
        return all.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /** [shiftMs] kept within ±[MOST_MS], on a whole number of [STEP_MS]. */
    fun snap(shiftMs: Long): Long =
        ((shiftMs.toDouble() / STEP_MS).roundToLong() * STEP_MS).coerceIn(-MOST_MS, MOST_MS)

    /**
     * Where in their own timestamps the lyrics are when the song is at [positionMs]: never before
     * their start, where lyrics moved later would otherwise stand before 0 for their first moments
     * and the view, which reads positions as unsigned, took every line for sung.
     */
    fun lyricsPosition(positionMs: Long, shiftMs: Long): Long = (positionMs - shiftMs).coerceAtLeast(0L)

    /** Where in the song a line stamped [lineMs] is sung, once moved: what a tap on it seeks to. */
    fun songPosition(lineMs: Long, shiftMs: Long): Long = (lineMs + shiftMs).coerceAtLeast(0L)

    /** The shift in seconds with one decimal, as the bar shows it, without its sign: "0.4", "0,4" in French. */
    fun seconds(shiftMs: Long, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", abs(shiftMs) / 1000.0)

    private fun parse(raw: String): Map<String, Long> =
        raw.split(",").mapNotNull { pair ->
            val at = pair.lastIndexOf('=')
            if (at <= 0) null else pair.substring(at + 1).toLongOrNull()?.let { pair.substring(0, at) to it }
        }.toMap()
}
