/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TidyPassTest {
    private data class Card(val id: String, val title: String, val artist: String?)
    private data class Tile(val name: String)   // an album or artist card: no song to compare

    private fun TidyPass.cards(items: List<Card>, fresh: Boolean = false) = row(items, fresh, { it.id }, { it.title }, { it.artist })

    @Test
    fun `just played songs and their versions leave the fresh row only`() {
        val pass = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        val picks = listOf(Card("a1", "Africa", "Toto"), Card("a2", "Africa (Live)", "Toto"), Card("h1", "Hold the Line", "Toto"))
        assertEquals(listOf("h1"), pass.cards(picks, fresh = true).map { it.id })
        val later = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        assertEquals(listOf("a1", "a2", "h1").size - 1, later.cards(picks).size)   // not fresh: only the version rule applies
    }

    @Test
    fun `a song shown once is not shown again below, nor is a version of it`() {
        val pass = TidyPass(emptyList())
        assertEquals(listOf("a1"), pass.cards(listOf(Card("a1", "Africa", "Toto"))).map { it.id })
        assertEquals(listOf("r1"), pass.cards(listOf(Card("a1", "Africa", "Toto"), Card("a3", "Africa - Remastered 2019", "Toto"), Card("r1", "Rosanna", "Toto"))).map { it.id })
    }

    @Test
    fun `the same title by another artist is a different song`() {
        val pass = TidyPass(listOf(PlayedSong("a1", "Africa", "Toto")))
        assertEquals(listOf("d1"), pass.cards(listOf(Card("d1", "Africa", "D'Angelo")), fresh = true).map { it.id })
    }

    @Test
    fun `within one row the first of two versions stays`() {
        val pass = TidyPass(emptyList())
        assertEquals(listOf("a2"), pass.cards(listOf(Card("a2", "Africa (Official Video)", "Toto"), Card("a1", "Africa", "Toto"))).map { it.id })
    }

    @Test
    fun `things that are not songs pass through`() {
        val pass = TidyPass(listOf(PlayedSong("x", "Anything", null)))
        val tiles = listOf(Tile("Toto IV"), Tile("Toto"))
        assertEquals(tiles, pass.row(tiles, freshOnly = true, { null }, { null }, { null }))
    }

    @Test
    fun `the pass is deterministic`() {
        val rows = listOf(Card("a1", "Africa", "Toto"), Card("a2", "Africa (Live)", "Toto"), Card("h1", "Hold the Line", "Toto"))
        val one = TidyPass(emptyList()).cards(rows)
        val two = TidyPass(emptyList()).cards(rows)
        assertEquals(one, two)
        assertEquals(listOf("a1", "h1"), one.map { it.id })
    }

    @Test
    fun `keys ignore case and spacing around the artist`() {
        assertEquals(versionKey("Africa", "Toto"), versionKey("AFRICA (Remastered)", " toto "))
    }
}
