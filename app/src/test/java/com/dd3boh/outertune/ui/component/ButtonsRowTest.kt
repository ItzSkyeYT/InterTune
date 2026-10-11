/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row of buttons too wide for its dialog by a little closes up and stays on one line, and only
 * one whose labels do not fit goes on two. What decides is here, in pixels, three to a dp as on
 * the phone it was first seen on; the measuring itself needs a device.
 */
class ButtonsRowTest {

    // The lyrics search dialog in French: Annuler, 8dp, Rechercher en ligne, 8dp, OK. Material
    // makes OK 58dp wide, and it could be 48.
    private val cancel = RowPart(natural = 243)
    private val gap = RowPart(natural = 24, gap = true)
    private val searchOnline = RowPart(natural = 453)
    private val ok = RowPart(natural = 174, tight = 144)
    private val lyricsSearch = listOf(cancel, gap, searchOnline, gap, ok)
    private val lyricsSearchWidth = 243 + 24 + 453 + 24 + 174

    @Test
    fun `buttons that fit are on one line at their own widths`() {
        val lines = buttonLines(start = emptyList(), end = lyricsSearch, room = lyricsSearchWidth)
        assertEquals(listOf(ButtonsLine(start = emptyList(), end = listOf(243, 24, 453, 24, 174))), lines)
    }

    /** What was seen: 1dp too wide on a phone of 360dp, and OK alone on a second line. */
    @Test
    fun `a row a little too wide closes its gaps and stays on one line`() {
        val lines = buttonLines(start = emptyList(), end = lyricsSearch, room = lyricsSearchWidth - 3)
        assertEquals(listOf(ButtonsLine(start = emptyList(), end = listOf(243, 23, 453, 22, 174))), lines)
    }

    @Test
    fun `the gaps close all the way before a button is made any narrower`() {
        val lines = buttonLines(start = emptyList(), end = lyricsSearch, room = lyricsSearchWidth - 48)
        assertEquals(listOf(ButtonsLine(start = emptyList(), end = listOf(243, 0, 453, 0, 174))), lines)
    }

    @Test
    fun `then a button wider than its label needs gives that up, and no more of it than is missing`() {
        val lines = buttonLines(start = emptyList(), end = lyricsSearch, room = lyricsSearchWidth - 60)
        assertEquals(listOf(ButtonsLine(start = emptyList(), end = listOf(243, 0, 453, 0, 162))), lines)
    }

    @Test
    fun `a button is never made narrower than its label needs`() {
        val lines = buttonLines(start = emptyList(), end = lyricsSearch, room = lyricsSearchWidth - 78)
        assertEquals(listOf(ButtonsLine(start = emptyList(), end = listOf(243, 0, 453, 0, 144))), lines)
    }

    @Test
    fun `labels that do not fit go on a second line, and nothing is closed up for that`() {
        val lines = buttonLines(start = emptyList(), end = lyricsSearch, room = lyricsSearchWidth - 79)
        assertEquals(
            listOf(
                ButtonsLine(start = emptyList(), end = listOf(243, 24, 453, 24)),
                ButtonsLine(start = emptyList(), end = listOf(174)),
            ),
            lines,
        )
    }

    /** The slider dialog at a large font size: Réinitialiser at the start, OK and Annuler at the end. */
    @Test
    fun `what is at the start stays on the first line, with the buttons that fit beside it`() {
        val reset = RowPart(natural = 480)
        val okLarge = RowPart(natural = 174, tight = 150)
        val cancelLarge = RowPart(natural = 316)
        val lines = buttonLines(start = listOf(reset), end = listOf(okLarge, cancelLarge), room = 840)
        assertEquals(
            listOf(
                ButtonsLine(start = listOf(480), end = listOf(174)),
                ButtonsLine(start = emptyList(), end = listOf(316)),
            ),
            lines,
        )
    }

    /** The tour's bubble at a large font size was 2dp too wide, and its Next went under Skip. */
    @Test
    fun `how far a tour has got, Skip and Next stay on one line when the gap between them can give`() {
        val progress = RowPart(natural = 180)
        val skip = RowPart(natural = 252)
        val next = RowPart(natural = 384)
        val between = RowPart(natural = 12, gap = true)
        val lines = buttonLines(start = listOf(progress), end = listOf(skip, between, next), room = 180 + 252 + 12 + 384 - 6)
        assertEquals(listOf(ButtonsLine(start = listOf(180), end = listOf(252, 6, 384))), lines)
    }

    @Test
    fun `a line with something at each end is as wide as the row can be, any other as wide as what is on it`() {
        assertEquals(900, ButtonsLine(start = listOf(180), end = listOf(252, 12, 384)).width(room = 900))
        assertEquals(417, ButtonsLine(start = emptyList(), end = listOf(243, 174)).width(room = 900))
        assertEquals(480, ButtonsLine(start = listOf(480), end = emptyList()).width(room = 900))
    }

    @Test
    fun `a button wider than the whole row has the row, on a line of its own`() {
        val long = RowPart(natural = 1200, tight = 900)
        val lines = buttonLines(start = emptyList(), end = listOf(cancel, long, ok), room = 900)
        assertEquals(
            listOf(
                ButtonsLine(start = emptyList(), end = listOf(243)),
                ButtonsLine(start = emptyList(), end = listOf(900)),
                ButtonsLine(start = emptyList(), end = listOf(174)),
            ),
            lines,
        )
    }

    @Test
    fun `an empty row has one empty line`() {
        assertEquals(listOf(ButtonsLine(emptyList(), emptyList())), buttonLines(emptyList(), emptyList(), room = 900))
    }

    @Test
    fun `what is taken is taken from each by its share, exactly, and from none more than it has`() {
        assertEquals(listOf(1, 2), shared(3, most = listOf(24, 24)))
        assertEquals(listOf(24, 24), shared(48, most = listOf(24, 24)))
        assertEquals(listOf(0, 12, 0), shared(12, most = listOf(0, 30, 0)))
        assertEquals(listOf(0, 0), shared(0, most = listOf(24, 24)))
        for (amount in 0..60) {
            val taken = shared(amount, most = listOf(7, 0, 30, 1, 22))
            assertEquals(amount, taken.sum())
            assertTrue(taken.zip(listOf(7, 0, 30, 1, 22)).all { (took, had) -> took in 0..had })
        }
    }
}
