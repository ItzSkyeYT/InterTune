/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import java.time.LocalDate
import java.time.MonthDay

/**
 * When the Recap shows. Once a year, from one reveal day, it opens by itself over the app, as a
 * full-screen pop-up that can be closed, and only once. From then on a banner at the top of
 * Settings opens it whenever wanted, until the next year's comes. Plain dates, no clock: the
 * caller says what today is.
 */
object RecapCalendar {
    /** The first year there is a Recap of. */
    const val FIRST_YEAR = 2026

    /**
     * The reveal day, the same every year. A constant for now: 2 December, the first Wednesday of
     * December in 2026. Whether it stays the 2nd or moves with the first Wednesday each year is
     * not decided yet.
     */
    val REVEAL: MonthDay = MonthDay.of(12, 2)

    /**
     * The pop-up is not owed after this day of the next year: a Recap that springs up in March
     * is a stale one. The banner in Settings stays.
     */
    val POP_UP_UNTIL: MonthDay = MonthDay.of(1, 31)

    fun revealDay(year: Int): LocalDate = REVEAL.atYear(year)

    /**
     * The year whose Recap is on show on [today]: the latest whose reveal day has come, or null
     * before the first. [revealedEarly] is the developer options' "today is the reveal day" of a
     * debug build, which puts this year's on show at once.
     */
    fun yearOnShow(today: LocalDate, revealedEarly: Boolean = false): Int? {
        if (revealedEarly) return today.year
        val year = if (!today.isBefore(revealDay(today.year))) today.year else today.year - 1
        return year.takeIf { it >= FIRST_YEAR }
    }

    /**
     * Whether the pop-up is owed on [today]: a year is on show, its pop-up has not been shown yet
     * ([shownYear] is the last year whose was, 0 for none), and its season has not ended.
     */
    fun popUpOwed(today: LocalDate, shownYear: Int, revealedEarly: Boolean = false): Boolean {
        val year = yearOnShow(today, revealedEarly) ?: return false
        if (shownYear >= year) return false
        return revealedEarly || !today.isAfter(POP_UP_UNTIL.atYear(year + 1))
    }
}
