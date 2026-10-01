/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.res.Resources
import com.dd3boh.outertune.history.HistoryFormat
import com.dd3boh.outertune.ui.screens.StatsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ICU's patterns as java.time on a phone reads them. The JVM running these tests has a java.time
 * that knows B, which the phone's does not, so the patterns are checked for the letters the
 * phone's knows (desugar_jdk_libs 2.1.5, Java 11's) as well as formatted.
 */
class LocaleDateFormatTest {
    private val afternoon = LocalTime.of(14, 5)

    /** The pattern letters of Java 11's DateTimeFormatter, which desugar_jdk_libs 2.1.5 ships. */
    private val java11Letters = "GuyDMLdQqYwWEecFaHkKhmsSAnNgpVvzOXxZ".toSet()

    /** The letters a pattern uses as letters, outside its quotes. */
    private fun lettersOf(pattern: String): Set<Char> {
        var quoted = false
        return buildSet {
            for (c in pattern) {
                if (c == '\'') quoted = !quoted else if (!quoted && (c in 'A'..'Z' || c in 'a'..'z')) add(c)
            }
        }
    }

    /**
     * Patterns ICU 78 gives with the day period in them (for the skeletons Bhm and Bh), the shape
     * Android's ICU has given for hm in some locales: each with the locale and what it becomes.
     */
    private val dayPeriods = listOf(
        Triple(Locale.TAIWAN, "Bh:mm", "ah:mm"),
        Triple(Locale.TAIWAN, "Bh時", "ah時"),
        Triple(Locale.JAPAN, "BK:mm", "aK:mm"),
        Triple(Locale.KOREA, "B h:mm", "a h:mm"),
        Triple(Locale.KOREA, "B h시", "a h시"),
        Triple(Locale.UK, "h:mm B", "h:mm a"),
        Triple(Locale.GERMANY, "h 'Uhr' B", "h 'Uhr' a"),
        Triple(Locale.forLanguageTag("af"), "hh:mm B", "hh:mm a"),
        Triple(Locale.forLanguageTag("da"), "h.mm B", "h.mm a"),
        Triple(Locale.forLanguageTag("bg"), "h:mm 'ч'. B", "h:mm 'ч'. a"),
        Triple(Locale.forLanguageTag("gd"), "h:mmB", "h:mma"),
        Triple(Locale.CANADA_FRENCH, "h 'h' mm B", "h 'h' mm a"),
        // Letters inside quotes are text, and a, b and B among them stay as they are.
        Triple(Locale.forLanguageTag("ee"), "'ga' h 'aɖabaƒoƒo' mm 'le' B 'me'", "'ga' h 'aɖabaƒoƒo' mm 'le' a 'me'"),
    )

    @Test
    fun `the day period becomes AM and PM, which the phone's java time knows`() {
        for ((locale, pattern, expected) in dayPeriods) {
            val rewritten = LocaleDateFormat.forJavaTime(pattern)
            assertEquals(pattern, expected, rewritten)
            assertTrue(pattern, 'B' in lettersOf(pattern))
            assertTrue("$pattern: ${lettersOf(rewritten!!)}", java11Letters.containsAll(lettersOf(rewritten)))
            val text = LocaleDateFormat(locale, "hm", "h:mm a") { _, _ -> pattern }.format(afternoon)
            assertEquals(pattern, DateTimeFormatter.ofPattern(expected, locale).format(afternoon), text)
        }
    }

    @Test
    fun `what each locale reads in the afternoon`() {
        fun at(locale: Locale, pattern: String) = LocaleDateFormat(locale, "hm", "h:mm a") { _, _ -> pattern }.format(afternoon)
        assertEquals("下午2:05", at(Locale.TAIWAN, "Bh:mm"))
        assertEquals("午後2:05", at(Locale.JAPAN, "BK:mm"))
        assertEquals("2:05 pm", at(Locale.UK, "h:mm B"))
        assertEquals("2 Uhr PM", at(Locale.GERMANY, "h 'Uhr' B"))
    }

    @Test
    fun `a pattern that needs nothing is left alone`() {
        for (pattern in listOf("h:mm a", "HH:mm", "ah:mm", "a 'ga' h:mm", "EEEE d MMMM y", "EEEE, d 'de' MMMM", "M月d日EEEE", "EEEE d LLLL", "Gy年M月d日EEEE")) {
            assertEquals(pattern, LocaleDateFormat.forJavaTime(pattern))
        }
    }

    @Test
    fun `noon and midnight, and any number of day period letters, are one a`() {
        assertEquals("h:mm a", LocaleDateFormat.forJavaTime("h:mm b"))
        assertEquals("h:mm a", LocaleDateFormat.forJavaTime("h:mm BBBB"))
        assertEquals("ah:mm", LocaleDateFormat.forJavaTime("aBh:mm"))
    }

    @Test
    fun `text java time keeps for itself is quoted`() {
        // Toki Pona's patterns in ICU 78 begin with #, which java.time reserves; [ ] { } likewise.
        assertEquals("'#'h:mm a", LocaleDateFormat.forJavaTime("#h:mm a"))
        assertEquals("'['h']'", LocaleDateFormat.forJavaTime("[h]"))
        assertEquals("#2:05 PM", LocaleDateFormat(Locale.US, "hm", "HH:mm") { _, _ -> "#h:mm a" }.format(afternoon))
    }

    @Test
    fun `a letter java time does not know gives the fallback`() {
        // ICU 78 for Chinese and Korean with their own calendars: r and U are the related and cyclic years.
        assertNull(LocaleDateFormat.forJavaTime("rU年MMMMdEEEE"))
        assertNull(LocaleDateFormat.forJavaTime("r년(U년) MMMM d일(EEEE)"))
        val f = LocaleDateFormat(Locale.UK, "EEEEdMMMMy", "EEEE d MMMM y") { _, _ -> "rU年MMMMdEEEE" }
        assertEquals("Monday 28 September 2026", f.format(LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `nothing the pattern or the value can do throws`() {
        fun time(pattern: () -> String?) = LocaleDateFormat(Locale.US, "hm", "h:mm a") { _, _ -> pattern() }.format(afternoon)
        // A quote never closed, which ofPattern refuses.
        assertEquals("2:05 PM", time { "h:mm 'x" })
        // ICU giving nothing, or failing.
        assertEquals("2:05 PM", time { null })
        assertEquals("2:05 PM", time { throw IllegalStateException("no ICU") })
        // A pattern asking a time for a field it has not got.
        assertEquals("2:05 PM", time { "EEEE h:mm a" })
    }

    @Test
    fun `History and the Stats page read the day period`() {
        val history = HistoryFormat(Locale.TAIWAN, is24Hour = false, today = "今天", yesterday = "昨天") { _, _ -> "Bh:mm" }
        assertEquals("下午2:05", history.time(LocalDateTime.of(2026, 9, 28, 14, 5)))
        @Suppress("DEPRECATION")
        val stats = StatsFormat(Resources(null, null, null), Locale.TAIWAN, is24Hour = false) { _, skeleton ->
            if (skeleton == "ha") "Bh時" else "Bh:mm"
        }
        assertEquals("下午2:05", stats.timeOfDay(14 * 60 + 5))
        assertEquals("下午2時", stats.hour(14))
    }
}
