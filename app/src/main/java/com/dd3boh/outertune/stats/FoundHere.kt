/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.stats

import com.dd3boh.outertune.constants.PlayOrigin

/**
 * Found with InterTune, his idea of 6 Nov 2026: how many of the songs the listener liked were first
 * played to them by InterTune, and where those first plays came from. "Not searching for a song
 * manually": a song first heard from a search, the library, a playlist, an album or an artist page
 * does not count, nor one liked before InterTune first played it (a like brought over from YouTube
 * Music). Counted from the day the live log began, since a play written before it has no source.
 */
object FoundHere {

    /** Where a first play came from, in the page's four rows. */
    enum class Way { QUICK_PICKS, RADIOS, DISCOVER, HOME }

    /** The count since [since] (true UTC), and each way's share, most first. */
    data class Result(val since: Long, val total: Int, val byWay: List<Pair<Way, Int>>)

    /**
     * How long before its first play a like may be dated and still count: the like is stored to
     * the second as the phone's clock had it, the listen to the millisecond.
     */
    const val SLACK_MS = 60_000L

    /**
     * The way a first play found its song, or null when the listener picked it themselves. A
     * radio's songs carry the origin of where the radio was started, so after a song searched for
     * or played from a menu, the ones that came next (autoplay depth over 0) were InterTune's
     * picks; after a playlist, an album or the library they were the listener's own list.
     */
    fun way(origin: Int, autoplayDepth: Int): Way? = when (PlayOrigin.fromCode(origin)) {
        PlayOrigin.QUICK_PICKS, PlayOrigin.WIDGET -> Way.QUICK_PICKS
        PlayOrigin.DISCOVER -> Way.DISCOVER
        PlayOrigin.HOME_ROW -> Way.HOME
        PlayOrigin.RADIO -> Way.RADIOS
        PlayOrigin.SEARCH, PlayOrigin.MENU, PlayOrigin.RECOGNISED -> if (autoplayDepth > 0) Way.RADIOS else null
        else -> null
    }

    /** A like as true UTC: it is stored as the phone's wall clock written as if it were UTC (db/Converters). */
    fun likedAt(stored: Long, tzOffsetMin: Int): Long = stored - tzOffsetMin * 60_000L

    /** Null before the live log began ([since] null); a total of 0 when nothing qualifies yet. */
    fun compute(firsts: List<FoundFirstListen>, since: Long?): Result? {
        since ?: return null
        val counts = HashMap<Way, Int>()
        for (f in firsts.distinctBy { it.songId }) {
            val way = way(f.origin, f.autoplayDepth) ?: continue
            if (f.firstAt < since) continue
            if (likedAt(f.likedDate, f.tzOffsetMin) < f.firstAt - SLACK_MS) continue
            counts.merge(way, 1, Int::plus)
        }
        val byWay = counts.entries.sortedWith(compareByDescending<Map.Entry<Way, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { it.key to it.value }
        return Result(since, byWay.sumOf { it.second }, byWay)
    }
}
