/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.LyricsTimingKey
import com.dd3boh.outertune.constants.LyricsTimingOnTimeKey
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

    /**
     * How much later the lyrics came on screen than they were sung before the lyrics clock was put
     * right (LyricsClock, 25 Oct 2026), and so how much earlier the moves saved then made them:
     * what [caughtUp] gives back.
     */
    const val CLOCK_CAUGHT_UP_MS = 100L

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

    /**
     * [raw] with every song's move [CLOCK_CAUGHT_UP_MS] later, kept to the slider's range and
     * steps, and a song that comes back to 0 forgotten: a move saved against the late lyrics, as
     * it stands with them on time. See [LyricsTimingOnTime].
     */
    fun caughtUp(raw: String): String {
        if (raw.isEmpty()) return raw
        return parse(raw).entries
            .mapNotNull { (song, ms) -> snap(ms + CLOCK_CAUGHT_UP_MS).takeIf { it != 0L }?.let { "$song=$it" } }
            .joinToString(",")
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

/**
 * The lyrics timing saved before the lyrics clock was put right (25 Oct 2026), moved once by what
 * the clock had lost: 100 ms later. Those moves were made against lines that came on screen late,
 * most of them the 0.1 s earlier that made up for it, and with the lines on time they would now
 * come early. A song at -0.1 s goes back to 0 and is forgotten, -0.3 s becomes -0.2 s, +0.2 s
 * becomes +0.3 s.
 *
 * Run as the settings are first read, after NewInstallSongCache, which needs them empty. Once:
 * [LyricsTimingOnTimeKey] says so, written by the first launch of a build with the clock on time
 * whether anything was moved or not, so that a move made from then on is never moved again. A
 * backup from before it brings its moves and no mark, and has them moved as it is restored.
 */
object LyricsTimingOnTime : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[LyricsTimingOnTimeKey] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[LyricsTimingKey]?.let { this[LyricsTimingKey] = LyricsTiming.caughtUp(it) }
            this[LyricsTimingOnTimeKey] = true
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}
