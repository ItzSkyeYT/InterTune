/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import android.text.format.DateFormat
import com.dd3boh.outertune.utils.LocaleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** The heading History puts over one day of plays. */
sealed interface HistoryDay {
    data object Today : HistoryDay
    data object Yesterday : HistoryDay

    /** Any other day, with its year when that is not the current one. */
    data class On(val date: LocalDate, val withYear: Boolean) : HistoryDay
}

/** One day's plays, newest first. */
data class HistoryDayGroup<T>(val date: LocalDate, val day: HistoryDay, val plays: List<T>)

/**
 * History by day: Today, Yesterday, then every earlier day on its own, rather than This week, Last
 * week and whole months. Each play goes under the local date it started on, at the offset it was
 * played at, and days are calendar days, so the day a clock changes is still one day.
 */
object HistoryDays {
    fun dayOf(date: LocalDate, today: LocalDate): HistoryDay = when (date) {
        today -> HistoryDay.Today
        today.minusDays(1) -> HistoryDay.Yesterday
        else -> HistoryDay.On(date, withYear = date.year != today.year)
    }

    /**
     * [plays] by local start date, newest day first, each day newest play first. Within a day the
     * order is by true instant, which is what a day spent crossing time zones needs.
     */
    fun <T> group(plays: List<T>, today: LocalDate, start: (T) -> LocalDateTime, at: (T) -> Long): List<HistoryDayGroup<T>> =
        plays.sortedByDescending(at)
            .groupBy { start(it).toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { (date, dayPlays) -> HistoryDayGroup(date, dayOf(date, today), dayPlays) }
}

/**
 * Day headings and play times in the phone's locale and its 12 or 24 hour setting: "Monday 28
 * September" (with the year when it is not this one) and "14:05" or "2:05 PM". The same skeletons
 * the Stats page uses, so a date reads the same on both, through [LocaleDateFormat], so no locale's
 * pattern can break the screen. [pattern] turns a skeleton into a pattern for the locale; tests
 * give it fixed ones.
 */
class HistoryFormat(
    private val locale: Locale,
    private val is24Hour: Boolean,
    private val today: String,
    private val yesterday: String,
    private val pattern: (Locale, String) -> String? = { l, skeleton -> DateFormat.getBestDateTimePattern(l, skeleton) },
) {
    private val formats = HashMap<String, LocaleDateFormat>()

    private fun format(skeleton: String): LocaleDateFormat =
        formats.getOrPut(skeleton) { LocaleDateFormat(locale, skeleton, fallback(skeleton), pattern) }

    /** The day as the locale writes it inside a sentence: "lundi 28 septembre" in French, as in the queue's title. */
    fun day(day: HistoryDay): String = when (day) {
        HistoryDay.Today -> today
        HistoryDay.Yesterday -> yesterday
        is HistoryDay.On -> format(daySkeleton(day.withYear)).format(day.date)
    }

    /** The day over its plays, which starts with a capital in every language: "Lundi 28 septembre". */
    fun heading(day: HistoryDay): String = day(day).replaceFirstChar { it.titlecase(locale) }

    fun time(start: LocalDateTime): String = format(timeSkeleton(is24Hour)).format(start)

    companion object {
        fun daySkeleton(withYear: Boolean) = "EEEEdMMMM" + if (withYear) "y" else ""
        fun timeSkeleton(is24Hour: Boolean) = if (is24Hour) "Hm" else "hm"

        /** What a skeleton is written as when the locale's own pattern cannot be read. */
        fun fallback(skeleton: String) = when (skeleton) {
            "Hm" -> "HH:mm"
            "hm" -> "h:mm a"
            "EEEEdMMMMy" -> "EEEE d MMMM y"
            else -> "EEEE d MMMM"
        }
    }
}
