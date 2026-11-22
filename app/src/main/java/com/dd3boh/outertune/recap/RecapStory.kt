/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/** The line a countdown slide says about its song, under its plays. Numbers and days: the screen words it. */
sealed interface CountdownLine {
    /** "57 of its 62 plays came in August." */
    data class Month(val month: Int, val plays: Int, val of: Int) : CountdownLine

    /** "Played on 41 different days, more than any other song here." */
    data class Days(val days: Int) : CountdownLine

    /** "1,234 minutes in all, more than any song above it." */
    data class Minutes(val minutes: Long) : CountdownLine

    /** "66 plays on 5 September, the day you found it." */
    data class FoundDay(val plays: Int, val day: Long) : CountdownLine

    /** "12 times in a row on 4 July." */
    data class InARow(val times: Int, val day: Long) : CountdownLine

    /** "9 plays on 4 July, its biggest day." */
    data class BestDay(val plays: Int, val day: Long) : CountdownLine

    /** "First played on 3 June." */
    data class FirstHeard(val day: Long) : CountdownLine
}

/**
 * Something to say about each song of the countdown, from its own numbers, and each slide
 * something different where the songs allow it. The mock's lines were picked by hand from one
 * listener's numbers; these are the same kinds of line, chosen by rule.
 */
object RecapStory {
    /** A month that holds this share of a song's plays, of this many plays or more, is its month. */
    const val MONTH_SHARE = 0.6
    const val MONTH_MIN_PLAYS = 8

    /** A day, or a run, worth a line. */
    const val DAY_MIN_PLAYS = 3

    /**
     * One line for each of songs 2 to [top]'s last, in the countdown's order: the last first,
     * number two at the end. Number one has a slide of its own.
     */
    fun countdown(top: List<TopSong>): List<CountdownLine> {
        val used = HashSet<Class<out CountdownLine>>()
        return (top.lastIndex downTo 1).map { i ->
            val options = candidates(top, i)
            val line = options.firstOrNull { it.javaClass !in used } ?: options.first()
            used += line.javaClass
            line
        }
    }

    /** What can truly be said of the song at [i], the most telling first; never empty. */
    internal fun candidates(top: List<TopSong>, i: Int): List<CountdownLine> = buildList {
        val s = top[i]
        s.byMonth.maxByOrNull { it.value }?.let { (month, n) ->
            if (s.plays >= MONTH_MIN_PLAYS && n >= s.plays * MONTH_SHARE) add(CountdownLine.Month(month, n, s.plays))
        }
        if (top.indices.all { it == i || top[it].days < s.days }) add(CountdownLine.Days(s.days))
        if (i > 0 && (0 until i).all { top[it].ms < s.ms }) add(CountdownLine.Minutes(s.ms / Recaps.MINUTE_MS))
        if (s.bestDay == s.firstHeardDay && s.bestDayPlays >= DAY_MIN_PLAYS) add(CountdownLine.FoundDay(s.bestDayPlays, s.bestDay))
        if (s.inARow >= DAY_MIN_PLAYS) add(CountdownLine.InARow(s.inARow, s.inARowDay))
        if (s.bestDayPlays >= DAY_MIN_PLAYS) add(CountdownLine.BestDay(s.bestDayPlays, s.bestDay))
        add(CountdownLine.FirstHeard(s.firstHeardDay))
    }

    /**
     * Number one's day, for "On 4 July you played it 40 times in a row": its longest run back to
     * back when that is two or more, or else its biggest day when that had two plays or more.
     */
    fun numberOneDay(song: TopSong): NumberOneDay? = when {
        song.inARow >= 2 -> NumberOneDay(song.inARow, song.inARowDay, inARow = true)
        song.bestDayPlays >= 2 -> NumberOneDay(song.bestDayPlays, song.bestDay, inARow = false)
        else -> null
    }
}

/** [times] plays on [day], back to back when [inARow]. */
data class NumberOneDay(val times: Int, val day: Long, val inARow: Boolean)
