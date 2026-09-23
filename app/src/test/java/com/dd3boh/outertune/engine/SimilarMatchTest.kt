/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.engine.SimilarMatch.Neighbour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilarMatchTest {
    private fun index(vararg songs: Triple<String, String, String?>) =
        SimilarMatch.Index(songs.map { (id, title, artist) -> SongRow(id, title, null, artist) })

    @Test
    fun `a neighbour matches the song however the upload dressed it`() {
        val k = SimilarMatch.key("One More Time", "Daft Punk")
        assertEquals(k, SimilarMatch.key("Daft Punk - One More Time (Official Video)", "Daft Punk"))
        assertEquals(k, SimilarMatch.key("One More Time - Radio Edit", "Daft Punk & Romanthony"))
        assertEquals(k, SimilarMatch.key("One More Time feat. Romanthony", "Daft Punk"))
        assertEquals(SimilarMatch.key("Halo", "Beyonce"), SimilarMatch.key("Halo", "Beyoncé"))
    }

    @Test
    fun `the artist is part of the key`() {
        assertEquals(false, SimilarMatch.key("Africa", "Toto") == SimilarMatch.key("Africa", "Weezer"))
    }

    @Test
    fun `a key needs both a title and an artist`() {
        assertNull(SimilarMatch.key("One More Time", null))
        assertNull(SimilarMatch.key("One More Time", "  "))
        assertNull(SimilarMatch.key("(Official Video)", "Daft Punk"))
    }

    @Test
    fun `titles outside the Latin alphabet still match`() {
        val idx = index(Triple("jp", "夜に駆ける", "YOASOBI"))
        assertEquals(listOf("jp"), SimilarMatch.match("seed", "群青", "YOASOBI", listOf(Neighbour("夜に駆ける", "YOASOBI")), idx))
    }

    @Test
    fun `matches keep Last-fm's order, leave out the seed and its versions, and count each song once`() {
        val idx = index(
            Triple("seed", "Around the World", "Daft Punk"),
            Triple("seedLive", "Around the World (Live)", "Daft Punk"),
            Triple("a", "Digital Love", "Daft Punk"),
            Triple("b", "Music Sounds Better with You", "Stardust"),
            Triple("unrelated", "Digital Love", "Somebody Else"),
        )
        val neighbours = listOf(
            Neighbour("Music Sounds Better With You", "Stardust"),
            Neighbour("Around the World - Radio Edit", "Daft Punk"),
            Neighbour("Digital Love", "Daft Punk"),
            Neighbour("Digital Love (Remastered)", "Daft Punk"),
            Neighbour("Not In The Library", "Nobody"),
        )
        assertEquals(listOf("b", "a"), SimilarMatch.match("seed", "Around the World", "Daft Punk", neighbours, idx))
    }

    @Test
    fun `the fallback question keeps the title's punctuation and is only asked when it differs`() {
        assertEquals("Don't Stop Me Now", SimilarMatch.fallbackTitle("Queen - Don't Stop Me Now (Official Video)", "Queen"))
        assertEquals("Take on Me", SimilarMatch.fallbackTitle("Take on Me - 2016 Remaster", "a-ha"))
        assertEquals("Levitating", SimilarMatch.fallbackTitle("Levitating feat. DaBaby", "Dua Lipa"))
        assertNull(SimilarMatch.fallbackTitle("Take on Me", "a-ha"))
        assertNull(SimilarMatch.fallbackTitle("Initial D - Deja Vu", "Dave Rodgers"))
        assertNull(SimilarMatch.fallbackTitle("(Official Video)", "Anyone"))
    }

    @Test
    fun `Last-fm is asked with the whole artist first, then the first name, each with the plain title after`() {
        assertEquals(
            listOf(
                "Tyler, The Creator" to "EARFQUAKE (Official Video)", "Tyler, The Creator" to "EARFQUAKE",
                "Tyler" to "EARFQUAKE (Official Video)", "Tyler" to "EARFQUAKE",
            ),
            SimilarMatch.questions("EARFQUAKE (Official Video)", "Tyler, The Creator"),
        )
        assertEquals(listOf("Toto" to "Africa"), SimilarMatch.questions("Africa", "Toto"))
        assertEquals(emptyList<Pair<String, String>>(), SimilarMatch.questions("Africa", null))
    }

    @Test
    fun `only Latin accents fold, and separators match in any case`() {
        assertEquals(false, SimilarMatch.key("が", "YOASOBI") == SimilarMatch.key("か", "YOASOBI"))
        assertEquals("Daft Punk", SimilarMatch.firstArtist("Daft Punk X Somebody"))
        assertEquals("Daft Punk", SimilarMatch.firstArtist("Daft Punk Feat. Somebody"))
    }

    @Test
    fun `one neighbour maps to at most three uploads`() {
        val idx = index(*Array(5) { Triple("u$it", "Digital Love", "Daft Punk") })
        val got = SimilarMatch.match("seed", "Around the World", "Daft Punk", listOf(Neighbour("Digital Love", "Daft Punk")), idx)
        assertEquals(SimilarMatch.MAX_PER_NEIGHBOUR, got.size)
    }
}
