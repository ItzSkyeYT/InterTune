/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.stats

import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Found with InterTune (his idea of 6 Nov 2026): liked songs that InterTune played first, and where
 * that first play came from; "not searching for a song manually".
 */
class FoundHereTest {

    private val since = 1_790_000_000_000L
    private val hour = 3_600_000L
    private val paris = 60

    /** A liked song first heard at [at] from [origin], liked [likedAfter] later, the like stored as the wall clock in Paris. */
    private fun first(id: String, origin: PlayOrigin, at: Long = since + hour, likedAfter: Long = 5 * 60_000L, depth: Int = 0, offset: Int = paris) =
        FoundFirstListen(id, likedDate = at + likedAfter + offset * 60_000L, firstAt = at, tzOffsetMin = offset, origin = origin.code, autoplayDepth = depth)

    @Test
    fun `songs first played from Quick picks, the widget, Discover, Home rows and radios count, by way, most first`() {
        val r = FoundHere.compute(
            listOf(
                first("a", PlayOrigin.QUICK_PICKS), first("b", PlayOrigin.WIDGET), first("c", PlayOrigin.QUICK_PICKS),
                first("d", PlayOrigin.RADIO), first("e", PlayOrigin.DISCOVER), first("f", PlayOrigin.HOME_ROW),
                first("g", PlayOrigin.RADIO),
            ),
            since,
        )!!
        assertEquals(7, r.total)
        assertEquals(
            listOf(FoundHere.Way.QUICK_PICKS to 3, FoundHere.Way.RADIOS to 2, FoundHere.Way.DISCOVER to 1, FoundHere.Way.HOME to 1),
            r.byWay,
        )
        assertEquals(since, r.since)
    }

    @Test
    fun `a song searched for or picked from a list is the listener's, the songs a radio played after it are InterTune's`() {
        val r = FoundHere.compute(
            listOf(
                first("searched", PlayOrigin.SEARCH),
                first("after a search", PlayOrigin.SEARCH, depth = 2),
                first("after a menu's play", PlayOrigin.MENU, depth = 1),
                first("next in a playlist", PlayOrigin.PLAYLIST, depth = 3),
                first("next on an album", PlayOrigin.ALBUM, depth = 1),
                first("library", PlayOrigin.LIBRARY),
                first("unknown", PlayOrigin.UNKNOWN, depth = 4),
            ),
            since,
        )!!
        assertEquals(2, r.total)
        assertEquals(listOf(FoundHere.Way.RADIOS to 2), r.byWay)
    }

    @Test
    fun `a like from before the first play does not count, read as true time from the wall clock it is stored as`() {
        // Brought over from YouTube Music before InterTune ever played it.
        val imported = first("imported", PlayOrigin.QUICK_PICKS, likedAfter = -3 * 24 * hour)
        // Liked 30 minutes before the first play: stored as Paris's wall clock it reads 30 minutes
        // after, and only the offset of the play puts it back before.
        val early = first("early", PlayOrigin.QUICK_PICKS, likedAfter = -30 * 60_000L)
        assertTrue("stored, it looks later than the play", early.likedDate > early.firstAt)
        // Liked during its first seconds: within the slack.
        val atOnce = first("at once", PlayOrigin.QUICK_PICKS, likedAfter = -20_000L)
        // New York, five hours behind: stored, the like reads before the play although it came after.
        val newYork = first("new york", PlayOrigin.RADIO, likedAfter = 10 * 60_000L, offset = -300)
        assertTrue(newYork.likedDate < newYork.firstAt)
        val r = FoundHere.compute(listOf(imported, early, atOnce, newYork), since)!!
        assertEquals(2, r.total)
        assertEquals(listOf(FoundHere.Way.QUICK_PICKS to 1, FoundHere.Way.RADIOS to 1), r.byWay)
    }

    @Test
    fun `before the live log nothing has a source, and a song first heard twice at once counts once`() {
        assertNull(FoundHere.compute(listOf(first("a", PlayOrigin.QUICK_PICKS)), since = null))
        val r = FoundHere.compute(
            listOf(
                first("old", PlayOrigin.QUICK_PICKS, at = since - hour),
                first("twice", PlayOrigin.QUICK_PICKS), first("twice", PlayOrigin.QUICK_PICKS),
            ),
            since,
        )!!
        assertEquals(1, r.total)
        assertEquals(0, FoundHere.compute(emptyList(), since)!!.total)
    }

    @Test
    fun `the page reads it once, all time, and shows it only when something qualifies`() {
        val vm = File("src/main/java/com/dd3boh/outertune/viewmodels/StatsViewModel.kt").readText()
        assertTrue(vm.contains("FoundHere.compute(database.statsFoundFirstListens(), database.statsBounds().firstLiveAt)"))
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/StatsScreen.kt").readText()
        assertTrue(screen.contains("found?.takeIf { it.total > 0 }?.let { f ->"))
        // Above the period's findings and lists, right under the chips.
        assertTrue(screen.indexOf("key = \"foundWithInterTune\"") in 0 until screen.indexOf("statsInsights("))
    }
}
