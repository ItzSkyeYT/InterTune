/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Shelf's three looks (1 Nov 2026), from the mockups in
 * experiments/features/shelf-redo: Big cover (the default), Crate and Records, each chosen
 * in the widget's settings. WidgetLayoutTest walks every size in each look; these are the looks'
 * own promises: the mockups' four by three, the two flaws the mockups had, the list alone, and a
 * Shelf from before the looks.
 */
class WidgetLooksTest {

    private fun plan(w: Int, h: Int, settings: WidgetSettings, available: Int = 6, fontScale: Float = 1f) =
        WidgetLayout.plan(w, h, settings, available, fontScale)

    private val big = WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.BIG_COVER)
    private val crate = WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.CRATE)
    private val records = WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.RECORDS)

    @Test
    fun `a Shelf is a Big cover unless another look was picked, and one from before the looks is a Big cover`() {
        assertEquals(ShelfLook.BIG_COVER, WidgetSettings().look)
        assertEquals(ShelfLook.BIG_COVER, ShelfLook.of(null))
        assertEquals(ShelfLook.BIG_COVER, ShelfLook.of("A_LATER_LOOK"))
        assertEquals(ShelfLook.CRATE, ShelfLook.of("CRATE"))
        // A Shelf saved before there were looks: everything but the look.
        val prefs = mutablePreferencesOf().also {
            WidgetKeys.write(it, WidgetSettings(style = WidgetStyle.SHELF, list = WidgetList.RECENT, maxRows = 2))
            it.remove(WidgetKeys.LOOK)
        }
        val read = WidgetKeys.read(prefs)
        assertEquals(WidgetStyle.SHELF, read.style)
        assertEquals(ShelfLook.BIG_COVER, read.look)
        assertEquals(WidgetList.RECENT, read.list)
        assertEquals(WidgetShape.BIG_COVER, plan(400, 250, read).shape)
    }

    /**
     * Big cover at the mockup's size, a phone's four by three (360 by 240 dp): the cover big and
     * square at the start, two by three covers beside it, the heart in the pill.
     */
    @Test
    fun `Big cover at the mockup's four by three is the big cover with two by three covers beside it`() {
        val p = plan(360, 240, big)
        val look = p.look!!
        val hero = look.hero!!
        assertEquals(WidgetShape.BIG_COVER, p.shape)
        assertEquals(6, look.picks.size)
        assertTrue("the big cover about as the mockup's 196", hero.w in 190..210)
        val columns = look.picks.map { it.x }.distinct()
        val rows = look.picks.map { it.y }.distinct()
        assertEquals("two across", 2, columns.size)
        assertEquals("three down", 3, rows.size)
        assertTrue("beside it", look.picks.all { it.x > hero.right })
        assertEquals("aligned with it", hero.y, rows.min())
        assertEquals(hero.bottom, look.picks.maxOf { it.bottom })
        assertTrue(p.like && p.skips && p.play)
        assertEquals("6 dp between the covers", 6, look.picks[1].x - look.picks[0].right)
        assertEquals(12, look.pickRadius)
        assertTrue("rounded 22 dp", look.heroRadius in 20..24)
    }

    /**
     * The mockup's title over the cover fought the cover's own lettering ("M83." through "Midnight
     * City"): the band under the words is solid all the way past them now, and dark enough that
     * white words win over a white one.
     */
    @Test
    fun `Big cover's words sit on a band solid enough that no cover can fight them`() {
        assertTrue(WidgetPictures.BAND_ALPHA >= 0.7f)
        for ((w, h) in listOf(150 to 150, 360 to 240, 400 to 188, 324 to 324, 900 to 600)) for (font in listOf(1f, 1.3f)) {
            val p = plan(w, h, big, fontScale = font)
            val look = p.look ?: continue
            val words = look.words!!
            assertTrue("$w x $h: the band reaches past the words", look.hero!!.y + look.band >= words.bottom)
        }
        // A white cover's lettering under the band is at most this light against white words: well apart.
        val under = (1 - WidgetPictures.BAND_ALPHA) * 255
        assertTrue(under < 80)
    }

    /**
     * The mockup's crate cut its title to "Midnight ..." because the pill took the width. The title
     * has its room first: wherever the pill sits beside the title, the title keeps
     * [WidgetLooks.TITLE_ROOM_DP] at the text's size, and elsewhere the pill is under it.
     */
    @Test
    fun `the crate's title gets its room before the pill does`() {
        for (w in 200..1100 step 7) for (h in listOf(170, 240, 340, 600)) for (font in listOf(1f, 1.3f)) for (size in WidgetTextSize.entries) {
            for (s in listOf(crate, records)) for (like in listOf(true, false)) {
                val p = plan(w, h, s.copy(textSize = size, showLike = like), fontScale = font)
                val look = p.look ?: continue
                val title = WidgetFill.lookLines(p, size.scale * font).firstOrNull { it.first == "title" }?.second ?: continue
                assertTrue("$w x $h font $font $size: ${title.w}", title.w >= (WidgetLooks.TITLE_ROOM_DP * size.scale * font).toInt() || p.glass == null || p.glass!!.y >= title.bottom)
                val g = p.glass
                if (look.pillAtEnd && g != null && g.y < title.bottom && g.y + g.h > title.y) assertTrue(g.x >= title.x + title.w)
            }
        }
        // At the mockup's size the pill is not on the title's line, and the title has all of it.
        val mock = plan(360, 240, crate)
        val words = mock.look!!.words!!
        assertTrue(mock.glass!!.y >= words.y + WidgetLayout.line(mock.titleSp, 1f))
        assertTrue(words.w >= WidgetLooks.TITLE_ROOM_DP)
    }

    @Test
    fun `Records at the mockup's four by three is the sleeve with its record out, the words and pill beside, and records under`() {
        val p = plan(360, 240, records)
        val look = p.look!!
        assertEquals(WidgetShape.RECORDS, p.shape)
        val hero = look.hero!!
        assertTrue("the record comes out", look.discOut >= hero.w / 4)
        assertTrue("the words beside the record", look.words!!.x >= hero.right + look.discOut)
        assertTrue("records under it", look.picks.all { it.y >= hero.bottom })
        assertTrue(look.picks.size >= 5)
        assertTrue(p.play && p.skips)
        assertTrue("records are never bigger than the sleeve", look.picks.all { it.w <= hero.w })
    }

    /** With a list alone: Big cover puts the first song big with its name over it and the rest beside; Crate and Records their heading over the crate or the records. */
    @Test
    fun `a list alone is the first song big in Big cover, and the heading over the crate or the records in the others`() {
        val b = plan(400, 250, big.copy(content = WidgetContent.LIST))
        val look = b.look!!
        assertNotNull(look.hero)
        assertTrue("the heading over the name", look.headingInWords > 0)
        assertEquals(b.shown, look.picks.size + 1)
        assertFalse(b.play)
        assertEquals(0, plan(400, 250, big.copy(content = WidgetContent.LIST, showHeading = false)).look!!.headingInWords)
        for (s in listOf(crate, records)) {
            val p = plan(400, 250, s.copy(content = WidgetContent.LIST))
            val l = p.look!!
            assertNull(l.hero)
            assertNotNull(l.heading)
            assertTrue(l.picks.all { it.y >= l.heading!!.bottom })
            assertNull(plan(400, 250, s.copy(content = WidgetContent.LIST, showHeading = false)).look!!.heading)
        }
    }

    /** The pill is one pane of glass round the buttons, as wide as they are: never wider, never a pane per button. */
    @Test
    fun `the pill is one pane as wide as its buttons`() {
        for (s in listOf(big, crate, records)) for ((w, h) in listOf(360 to 240, 400 to 188, 324 to 324, 405 to 432, 900 to 600)) {
            val p = plan(w, h, s)
            val g = p.glass ?: continue
            if (g.circle) continue
            assertEquals("$s $w x $h", WidgetLayout.rowWidth(p) + 2 * WidgetLayout.glassPadH(p), g.w)
        }
        // A pill is not a sheet: the most it takes of Big cover's cover is its width, never its height.
        val p = plan(360, 240, big)
        assertTrue(p.glass!!.h < p.look!!.hero!!.h / 3)
    }

    /** Small sizes keep what they can of a look, then draw a line or a tile as before. */
    @Test
    fun `small sizes keep what they can of a look, then fall back to a line or a tile`() {
        for (s in listOf(big, crate, records)) {
            assertEquals(WidgetShape.TILE, plan(70, 70, s).shape)
            assertEquals(WidgetShape.LINE, plan(407, 50, s).shape)
            assertEquals(WidgetShape.LINE, plan(324, 108, s).shape)
        }
        // Two by two is Big cover's cover alone, with play.
        val two = plan(150, 150, big)
        assertEquals(WidgetShape.BIG_COVER, two.shape)
        assertTrue(two.play)
        // The crate and the records from a widget a little wider than a phone's two cells.
        assertTrue(plan(243, 216, crate).look != null)
        assertTrue(plan(243, 216, records).look != null)
    }

    /** The old Shelf's drawing is gone with its shapes. */
    @Test
    fun `the old shelf's drawing is gone`() {
        val widget = File("src/main/java/com/dd3boh/outertune/widget/MusicWidget.kt").readText()
        for (gone in listOf("private fun Shelf(", "private fun Grid(", "private fun ShelfCovers(")) assertFalse(gone, gone in widget)
        assertEquals(setOf("BIG_COVER", "CRATE", "RECORDS"), WidgetShape.entries.map { it.name }.filter { it in setOf("BIG_COVER", "CRATE", "RECORDS", "SHELF", "GRID") }.toSet())
    }
}
