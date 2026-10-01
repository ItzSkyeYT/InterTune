/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class HistoryDaysTest {
    /** A play as History sees it: when it started on the wall clock, and as an instant. */
    private data class P(val name: String, val start: LocalDateTime, val at: Long)

    private fun play(name: String, start: LocalDateTime, offsetMin: Int = 60) =
        P(name, start, start.toInstant(ZoneOffset.ofTotalSeconds(offsetMin * 60)).toEpochMilli())

    private fun group(today: LocalDate, vararg plays: P) =
        HistoryDays.group(plays.toList(), today, { it.start }, { it.at })
            .map { g -> g.day to g.plays.map { it.name } }

    /** What getBestDateTimePattern gives in en-GB for the skeletons History asks for. */
    private val britishPatterns = mapOf(
        "EEEEdMMMM" to "EEEE d MMMM",
        "EEEEdMMMMy" to "EEEE d MMMM y",
        "Hm" to "HH:mm",
        "hm" to "h:mm a",
    )

    private fun format(is24Hour: Boolean, locale: Locale = Locale.UK) =
        HistoryFormat(locale, is24Hour, "Today", "Yesterday") { _, skeleton -> britishPatterns.getValue(skeleton) }

    @Test
    fun `today, yesterday, then each earlier day on its own`() {
        val today = LocalDate.of(2026, 10, 1)
        assertEquals(HistoryDay.Today, HistoryDays.dayOf(today, today))
        assertEquals(HistoryDay.Yesterday, HistoryDays.dayOf(LocalDate.of(2026, 9, 30), today))
        assertEquals(HistoryDay.On(LocalDate.of(2026, 9, 29), withYear = false), HistoryDays.dayOf(LocalDate.of(2026, 9, 29), today))
        assertEquals(HistoryDay.On(LocalDate.of(2026, 8, 3), withYear = false), HistoryDays.dayOf(LocalDate.of(2026, 8, 3), today))
        assertEquals(HistoryDay.On(LocalDate.of(2025, 12, 31), withYear = true), HistoryDays.dayOf(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun `days come newest first and plays newest first within each`() {
        val today = LocalDate.of(2026, 10, 1)
        val groups = group(
            today,
            play("a", LocalDateTime.of(2026, 9, 28, 9, 0)),
            play("b", LocalDateTime.of(2026, 10, 1, 8, 0)),
            play("c", LocalDateTime.of(2026, 9, 30, 22, 0)),
            play("d", LocalDateTime.of(2026, 10, 1, 9, 30)),
            play("e", LocalDateTime.of(2026, 9, 28, 18, 0)),
        )
        assertEquals(
            listOf(
                HistoryDay.Today to listOf("d", "b"),
                HistoryDay.Yesterday to listOf("c"),
                HistoryDay.On(LocalDate.of(2026, 9, 28), false) to listOf("e", "a"),
            ),
            groups,
        )
    }

    @Test
    fun `midnight belongs to the day it starts`() {
        val today = LocalDate.of(2026, 10, 1)
        val groups = group(
            today,
            play("late", LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)),
            play("midnight", LocalDateTime.of(2026, 10, 1, 0, 0)),
        )
        assertEquals(listOf(HistoryDay.Today to listOf("midnight"), HistoryDay.Yesterday to listOf("late")), groups)
    }

    @Test
    fun `yesterday is the calendar day before, even across a clock change`() {
        // Paris skipped from 02:00 to 03:00 on 29 March 2026. Twenty three hours before ten past
        // midnight on the 30th is still the 29th, and the 28th is not yesterday.
        val today = LocalDate.of(2026, 3, 30)
        val groups = group(
            today,
            play("now", LocalDateTime.of(2026, 3, 30, 0, 10), offsetMin = 120),
            play("after change", LocalDateTime.of(2026, 3, 29, 3, 0), offsetMin = 120),
            play("before change", LocalDateTime.of(2026, 3, 29, 1, 59), offsetMin = 60),
            play("night before", LocalDateTime.of(2026, 3, 28, 23, 50), offsetMin = 60),
        )
        assertEquals(
            listOf(
                HistoryDay.Today to listOf("now"),
                HistoryDay.Yesterday to listOf("after change", "before change"),
                HistoryDay.On(LocalDate.of(2026, 3, 28), false) to listOf("night before"),
            ),
            groups,
        )
    }

    @Test
    fun `the autumn change repeats an hour, and the day still holds both`() {
        // Paris went back from 03:00 to 02:00 on 25 October 2026: 02:30 happened twice. The
        // instant puts them in the order they were played.
        val today = LocalDate.of(2026, 10, 25)
        val groups = group(
            today,
            play("first 02:30", LocalDateTime.of(2026, 10, 25, 2, 30), offsetMin = 120),
            play("second 02:30", LocalDateTime.of(2026, 10, 25, 2, 30), offsetMin = 60),
        )
        assertEquals(listOf(HistoryDay.Today to listOf("second 02:30", "first 02:30")), groups)
    }

    @Test
    fun `across new year, yesterday is last year and the day before it says the year`() {
        val today = LocalDate.of(2027, 1, 1)
        val groups = group(
            today,
            play("new year", LocalDateTime.of(2027, 1, 1, 0, 1)),
            play("eve", LocalDateTime.of(2026, 12, 31, 23, 0)),
            play("before", LocalDateTime.of(2026, 12, 30, 12, 0)),
        )
        assertEquals(
            listOf(
                HistoryDay.Today to listOf("new year"),
                HistoryDay.Yesterday to listOf("eve"),
                HistoryDay.On(LocalDate.of(2026, 12, 30), withYear = true) to listOf("before"),
            ),
            groups,
        )
        assertEquals("Wednesday 30 December 2026", format(true).day(HistoryDay.On(LocalDate.of(2026, 12, 30), withYear = true)))
    }

    @Test
    fun `day headings in words`() {
        val f = format(true)
        assertEquals("Today", f.day(HistoryDay.Today))
        assertEquals("Yesterday", f.day(HistoryDay.Yesterday))
        assertEquals("Monday 28 September", f.day(HistoryDay.On(LocalDate.of(2026, 9, 28), withYear = false)))
        assertEquals("Sunday 28 September 2025", f.day(HistoryDay.On(LocalDate.of(2025, 9, 28), withYear = true)))
    }

    @Test
    fun `the skeletons asked for, with the year only when it is needed`() {
        assertEquals("EEEEdMMMM", HistoryFormat.daySkeleton(withYear = false))
        assertEquals("EEEEdMMMMy", HistoryFormat.daySkeleton(withYear = true))
        assertEquals("Hm", HistoryFormat.timeSkeleton(is24Hour = true))
        assertEquals("hm", HistoryFormat.timeSkeleton(is24Hour = false))
    }

    @Test
    fun `times on the phone's own clock`() {
        val midnight = LocalDateTime.of(2026, 9, 28, 0, 5)
        val afternoon = LocalDateTime.of(2026, 9, 28, 14, 30)
        assertEquals("00:05", format(true).time(midnight))
        assertEquals("14:30", format(true).time(afternoon))
        assertEquals("12:05 AM", format(false, Locale.US).time(midnight))
        assertEquals("2:30 PM", format(false, Locale.US).time(afternoon))
    }

    @Test
    fun `French day names come from the locale`() {
        val f = HistoryFormat(Locale.FRANCE, true, "Aujourd'hui", "Hier") { _, skeleton -> britishPatterns.getValue(skeleton) }
        assertEquals("lundi 28 septembre", f.day(HistoryDay.On(LocalDate.of(2026, 9, 28), withYear = false)))
    }
}
