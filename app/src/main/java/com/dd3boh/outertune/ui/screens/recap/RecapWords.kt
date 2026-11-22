/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.LocaleDateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/**
 * The Recap's numbers, days and times in the phone's language, and its sentences with the
 * numbers and names in them set in bold, as the mock has them.
 */
class RecapWords(private val context: Context, private val year: Int) {
    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val numbers = NumberFormat.getIntegerInstance(locale)
    private val dayMonth = LocaleDateFormat(locale, "dMMMM", "d MMMM")
    private val dayMonthYear = LocaleDateFormat(locale, "dMMMMy", "d MMMM y")
    private val dayShort = LocaleDateFormat(locale, "dMMM", "d MMM")
    private val monthShort = LocaleDateFormat(locale, "LLL", "LLL")
    private val monthLong = LocaleDateFormat(locale, "MMMM", "MMMM")
    private val clock = LocaleDateFormat(locale, "jm", "HH:mm")
    private val hourOnly = LocaleDateFormat(locale, "j", "HH")
    private val twelveHours = runCatching { DateFormat.getBestDateTimePattern(locale, "jm").contains('a') }.getOrDefault(false)

    fun number(n: Number): String = numbers.format(n)

    /** "14 June", with its year when that is not the Recap's. */
    fun day(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return if (date.year == year) dayMonth.format(date) else dayMonthYear.format(date)
    }

    /** "20 Sep". */
    fun dayShort(epochDay: Long): String = dayShort.format(LocalDate.ofEpochDay(epochDay))

    /** "Jun", for the ticks under a chart. */
    fun monthShort(epochDay: Long): String = monthShort.format(LocalDate.ofEpochDay(epochDay))

    /** "August", as it reads inside a sentence. */
    fun month(month: Int): String = monthLong.format(YearMonth.of(year, month).atDay(1))

    /** "11:31", or "11:31 AM" where the phone keeps twelve hours. */
    fun time(minuteOfDay: Int): String = clock.format(LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60))

    fun hour(hour: Int): String = time(hour * 60)

    /**
     * The hour as short as it reads, for the year's card where room is short: without its
     * minutes where the language counts twelve hours ("6 PM"), else as [hour] has it ("18:00").
     */
    fun hourShort(hour: Int): String = if (twelveHours) hourOnly.format(LocalTime.of(hour % 24, 0)) else hour(hour)

    fun minutes(ms: Long): Long = (ms + 30_000) / 60_000

    /** "8 h 7 min", "45 min". */
    fun length(ms: Long): String {
        val total = minutes(ms)
        val h = total / 60
        val m = total % 60
        return if (h > 0) context.getString(R.string.recap_hours_minutes, number(h), number(m))
        else context.getString(R.string.recap_minutes_only, number(m))
    }

    fun plays(n: Int): String = context.resources.getQuantityString(R.plurals.recap_plays, n, number(n))

    /**
     * The string [id] with its arguments put in, those marked true set in bold. The arguments are
     * %1$s to %9$s, in any order the language puts them.
     */
    fun bold(id: Int, vararg args: Pair<String, Boolean>): AnnotatedString = fill(context.getString(id), args)

    fun boldPlural(id: Int, quantity: Int, vararg args: Pair<String, Boolean>): AnnotatedString =
        fill(context.resources.getQuantityString(id, quantity), args)

    private fun fill(template: String, args: Array<out Pair<String, Boolean>>): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (m in PLACE.findAll(template)) {
            append(template.substring(at, m.range.first).replace("%%", "%"))
            val (text, strong) = args.getOrNull(m.groupValues[1].toInt() - 1) ?: ("" to false)
            if (strong) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) } else append(text)
            at = m.range.last + 1
        }
        append(template.substring(at).replace("%%", "%"))
    }

    private companion object {
        val PLACE = Regex("""%(\d)\$[sd]""")
    }
}
