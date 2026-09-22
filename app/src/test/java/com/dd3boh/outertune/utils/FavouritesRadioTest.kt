/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What a favourites mix has to do, stated as the properties rather than as one expected order.
 *
 * The function is random by design, so asserting a literal sequence would only pin the seed and
 * would break on any change to how the shuffling is done while saying nothing about whether the
 * result is still a mix. These assert the things that would make it stop being one.
 */
class FavouritesRadioTest {

    /** Artists named by letter, songs numbered, so a failure reads as "A1 B1 A2" and is obvious. */
    private fun artist(name: String, count: Int): List<String> = (1..count).map { "$name$it" }

    private fun artistOf(song: String) = song.takeWhile { !it.isDigit() }

    @Test
    fun `every song survives exactly once`() {
        val input = listOf(artist("A", 200), artist("B", 3), artist("C", 17))
        val out = interleaveByArtist(input, Random(1))

        assertEquals(220, out.size)
        assertEquals(input.flatten().toSet(), out.toSet())
        assertEquals("no song is repeated", out.size, out.toSet().size)
    }

    @Test
    fun `no artist plays twice in a row while another still has songs`() {
        val input = listOf(artist("A", 50), artist("B", 50), artist("C", 50))

        // Many seeds, because a boundary collision is exactly the kind of fault that shows up on
        // one arrangement in ten and would otherwise ride out on a lucky default.
        for (seed in 1..200) {
            val out = interleaveByArtist(input, Random(seed))
            for (i in 1 until out.size) {
                assertTrue(
                    "seed $seed: ${out[i - 1]} then ${out[i]} are the same artist, back to back",
                    artistOf(out[i - 1]) != artistOf(out[i]),
                )
            }
        }
    }

    @Test
    fun `a huge artist does not crowd out a small one at the start`() {
        // The lopsided library the feature exists for: a plain shuffle over these 203 songs puts
        // roughly 98 A-songs before the first B.
        val input = listOf(artist("A", 200), artist("B", 3))

        for (seed in 1..50) {
            val out = interleaveByArtist(input, Random(seed))
            val firstB = out.indexOfFirst { artistOf(it) == "B" }
            assertTrue("seed $seed: B did not appear in the first two songs", firstB < 2)

            // B has three songs, so B should be heard three times inside the first six, not
            // stretched out across the whole two hundred.
            val bInFirstSix = out.take(6).count { artistOf(it) == "B" }
            assertEquals("seed $seed: B should be spent early", 3, bInFirstSix)
        }
    }

    @Test
    fun `the tail is the leftover of the biggest artist`() {
        // Once everybody else is spent there is nothing to interleave with, so consecutive songs
        // by the remaining artist are correct rather than a failure.
        val out = interleaveByArtist(listOf(artist("A", 10), artist("B", 2)), Random(7))

        assertEquals(12, out.size)
        assertTrue("B is done within the first four", out.take(4).count { artistOf(it) == "B" } == 2)
        assertTrue("the tail is all A", out.drop(4).all { artistOf(it) == "A" })
    }

    @Test
    fun `order varies between runs`() {
        val input = listOf(artist("A", 20), artist("B", 20), artist("C", 20))
        val runs = (1..20).map { interleaveByArtist(input, Random(it)) }

        assertTrue("different seeds gave identical orders", runs.toSet().size > 1)
    }

    @Test
    fun `the same seed gives the same order`() {
        val input = listOf(artist("A", 20), artist("B", 5))

        assertEquals(interleaveByArtist(input, Random(42)), interleaveByArtist(input, Random(42)))
    }

    @Test
    fun `songs within one artist are not left in database order`() {
        // A caller that forgets to shuffle would otherwise ship every artist playing alphabetically.
        val input = listOf(artist("A", 40))
        val out = interleaveByArtist(input, Random(3))

        assertEquals(40, out.size)
        assertTrue("A came out in its original order", out != input.first())
    }

    @Test
    fun `grouping by key reproduces the same mix as grouping by hand`() {
        val flat = artist("A", 30) + artist("B", 4) + artist("C", 9)

        val grouped = interleaveBy(flat, Random(5)) { artistOf(it) }

        assertEquals(43, grouped.size)
        assertEquals(flat.toSet(), grouped.toSet())
        for (i in 1 until grouped.size) {
            val stillMixed = grouped.drop(i).any { artistOf(it) != artistOf(grouped[i]) }
            if (stillMixed) {
                assertTrue(
                    "${grouped[i - 1]} then ${grouped[i]} are the same artist",
                    artistOf(grouped[i - 1]) != artistOf(grouped[i]),
                )
            }
        }
    }

    @Test
    fun `an item with no artist is dropped rather than pooled`() {
        // A song that reached the list with no bookmarked artist is a query fault. Pooling those
        // into one anonymous group would play them and hide the fault.
        val flat = listOf("A1", "A2", "orphan", "B1", "B2")

        val out = interleaveBy(flat, Random(1)) { it.takeWhile { c -> !c.isDigit() }.ifEmpty { null }
            ?.takeIf { name -> name.length == 1 } }

        assertEquals(4, out.size)
        assertTrue("the orphan was played", "orphan" !in out)
        assertEquals(setOf("A1", "A2", "B1", "B2"), out.toSet())
    }

    @Test
    fun `empty and degenerate inputs do not throw`() {
        assertEquals(emptyList<String>(), interleaveByArtist(emptyList<List<String>>(), Random(1)))
        assertEquals(emptyList<String>(), interleaveByArtist(listOf(emptyList<String>()), Random(1)))
        assertEquals(listOf("A1"), interleaveByArtist(listOf(artist("A", 1)), Random(1)))

        // An artist with nothing in the library should not occupy a slot in the rotation.
        val out = interleaveByArtist(listOf(artist("A", 2), emptyList(), artist("B", 2)), Random(1))
        assertEquals(4, out.size)
        for (i in 1 until out.size) {
            assertTrue("empty artist left a gap that paired ${out[i - 1]} with ${out[i]}",
                artistOf(out[i - 1]) != artistOf(out[i]))
        }
    }
}
