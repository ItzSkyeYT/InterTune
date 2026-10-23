/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecapStoryTest {
    private val minute = Recaps.MINUTE_MS

    private fun song(
        id: String,
        plays: Int,
        minutes: Long = plays * 3L,
        days: Int = 2,
        firstHeardDay: Long = 100,
        bestDay: Long = 120,
        bestDayPlays: Int = 1,
        inARow: Int = 1,
        byMonth: Map<Int, Int> = mapOf(6 to plays / 2, 7 to plays - plays / 2),
    ) = TopSong(id, plays, minutes * minute, firstHeardDay, 0, days, bestDay, bestDayPlays, inARow, bestDay, byMonth)

    @Test
    fun `each song from the fifth to the second gets a line, the fifth first`() {
        val top = listOf(
            song("one", 90),
            song("two", 87, bestDay = 150, firstHeardDay = 150, bestDayPlays = 66),
            song("three", 66, minutes = 400),
            song("four", 63, days = 30),
            song("five", 62, byMonth = mapOf(8 to 57, 9 to 5)),
        )
        assertEquals(
            listOf(
                CountdownLine.Month(8, 57, 62),
                CountdownLine.Days(30),
                CountdownLine.Minutes(400),
                CountdownLine.FoundDay(66, 150),
            ),
            RecapStory.countdown(top),
        )
    }

    @Test
    fun `no two slides say the same kind of thing while another kind is true`() {
        // Both songs have a big day: the second one falls back to its run.
        val top = listOf(
            song("one", 50),
            song("two", 40, bestDayPlays = 9, inARow = 4),
            song("three", 30, bestDayPlays = 7),
        )
        val lines = RecapStory.countdown(top)
        assertEquals(CountdownLine.BestDay(7, 120), lines[0])
        assertEquals(CountdownLine.InARow(4, 120), lines[1])
    }

    @Test
    fun `with nothing else to say, when it was first played`() {
        val top = listOf(song("one", 10), song("two", 5, firstHeardDay = 77))
        assertEquals(listOf(CountdownLine.FirstHeard(77)), RecapStory.countdown(top))
    }

    @Test
    fun `a month needs most of the plays and enough of them`() {
        assertEquals(CountdownLine.FirstHeard(100), RecapStory.candidates(listOf(song("a", 9), song("b", 7, byMonth = mapOf(8 to 7))), 1).first())
        assertEquals(CountdownLine.Month(8, 8, 8), RecapStory.candidates(listOf(song("a", 9), song("b", 8, byMonth = mapOf(8 to 8))), 1).first())
    }

    @Test
    fun `one song alone has no countdown`() {
        assertEquals(emptyList<CountdownLine>(), RecapStory.countdown(listOf(song("one", 10))))
    }

    @Test
    fun `number one's day is its run, or its biggest day, or nothing`() {
        assertEquals(NumberOneDay(40, 120, inARow = true), RecapStory.numberOneDay(song("a", 90, inARow = 40, bestDayPlays = 44)))
        assertEquals(NumberOneDay(6, 120, inARow = false), RecapStory.numberOneDay(song("a", 90, bestDayPlays = 6)))
        assertNull(RecapStory.numberOneDay(song("a", 3)))
    }
}
