/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HomePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPicksShelfTest {
    private fun song(id: String, duration: Int?) = SongItem(id, "Song $id", listOf(Artist("Artist", null)), duration = duration, thumbnail = "")

    private fun shelf(title: String, vararg durations: Int?, perColumn: Int? = 4) =
        HomePage.Section(title, null, null, null, durations.mapIndexed { i, d -> song("$title$i", d) }, itemsPerColumn = perColumn)

    private fun songs(title: String) = shelf(title, 180, 200, 240, 210, null)
    private fun mixes(title: String) = shelf(title, 3600, 4200, 7200, 5400, 300)

    @Test
    fun `the shelf titled Quick picks wins over an earlier shelf of songs`() {
        val page = HomePage(null, listOf(songs("Covers and remixes"), songs("Quick picks"), songs("Trending songs for you")))
        val lift = QuickPicksShelf.choose(page, "Quick picks")!!
        assertEquals("Quick picks", lift.section.title)
        assertTrue(lift.titled)
    }

    @Test
    fun `the title match ignores case and surrounding space`() {
        val page = HomePage(null, listOf(shelf(" quick PICKS ", 200, 220)))
        assertTrue(QuickPicksShelf.choose(page, "Quick picks")!!.titled)
    }

    @Test
    fun `without the title the first shelf that is mostly songs is taken and a shelf of mixes is passed over`() {
        val page = HomePage(null, listOf(mixes("Long listens"), songs("Sélection rapide")))
        val lift = QuickPicksShelf.choose(page, "Quick picks")!!
        assertEquals("Sélection rapide", lift.section.title)
        assertFalse(lift.titled)
    }

    @Test
    fun `a feed with only mixes gives nothing rather than a row of mixes`() {
        assertNull(QuickPicksShelf.choose(HomePage(null, listOf(mixes("Long listens"))), "Quick picks"))
    }

    @Test
    fun `a card shelf and a shelf with an album in it are not candidates`() {
        val cards = shelf("New releases", 200, 210, perColumn = null)
        val mixed = HomePage.Section("Albums for you", null, null, null, listOf(song("x", 200), AlbumItem(browseId = "b", playlistId = "p", title = "Album", artists = listOf(Artist("A", null)), thumbnail = "")), itemsPerColumn = 4)
        assertNull(QuickPicksShelf.choose(HomePage(null, listOf(cards, mixed)), "Quick picks"))
    }

    @Test
    fun `an item without a duration counts as a song`() {
        assertEquals(1.0, QuickPicksShelf.songShare(shelf("Quick picks", null, null, null)), 0.0)
        assertEquals(0.2, QuickPicksShelf.songShare(mixes("Long listens")), 1e-9)
    }

    @Test
    fun `a titled lift is never displaced and displaces a lift by content, and the first of two by content stays`() {
        val titled = QuickPicksShelf.Lift(songs("Quick picks"), titled = true)
        val byContent = QuickPicksShelf.Lift(songs("Covers and remixes"), titled = false)
        val later = QuickPicksShelf.Lift(songs("Trending songs for you"), titled = false)
        assertTrue(QuickPicksShelf.replaces(null, byContent))
        assertTrue(QuickPicksShelf.replaces(byContent, titled))
        assertFalse(QuickPicksShelf.replaces(titled, byContent))
        assertFalse(QuickPicksShelf.replaces(titled, QuickPicksShelf.Lift(songs("Quick picks"), titled = true)))
        assertFalse(QuickPicksShelf.replaces(byContent, later))
    }
}
