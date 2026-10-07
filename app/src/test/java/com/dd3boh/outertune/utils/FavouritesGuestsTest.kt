/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Similar artists joining the favourites mix, stated as what must stay true rather than as one
 * expected order, for the reason FavouritesRadioTest gives: the dealing is random by design.
 *
 * Favourites are upper case letters and guests lower case, so a failure reads as "A1 B1 x1 C1".
 */
class FavouritesGuestsTest {

    private fun artist(name: String, count: Int): List<String> = (1..count).map { "$name$it" }

    private fun artistOf(song: String) = song.takeWhile { !it.isDigit() }

    private fun isGuest(song: String) = song.first().isLowerCase()

    /** A candidate as the query hands it over: the song, and how many of the favourites' songs point at it. */
    private data class Candidate(val song: String, val refs: Int)

    private fun candidates(name: String, count: Int, refs: Int) = artist(name, count).map { Candidate(it, refs) }

    private fun queue(candidates: List<Candidate>, favouriteArtists: Int, seed: Int = 1) =
        guestQueue(candidates, favouriteArtists, Random(seed), { it.song }, { it.refs }) { artistOf(it.song).ifEmpty { null } }
            .map { it.song }

    // The dealing

    @Test
    fun `the favourites keep the order they had`() {
        val favourites = interleaveByArtist(listOf(artist("A", 40), artist("B", 9), artist("C", 21)), Random(4))
        val guests = artist("x", 10) + artist("y", 10) + artist("z", 10)

        for (seed in 1..50) {
            val out = dealGuests(favourites, guests, Random(seed))
            assertEquals("seed $seed", favourites, out.filterNot(::isGuest))
        }
    }

    @Test
    fun `one song in four is a guest and never more`() {
        val favourites = interleaveByArtist(listOf(artist("A", 50), artist("B", 50), artist("C", 20)), Random(2))
        val guests = artist("x", 100)

        for (seed in 1..50) {
            val out = dealGuests(favourites, guests, Random(seed))
            assertEquals("seed $seed: 120 favourites earn 40 guests", 160, out.size)
            // And not only in total: no stretch from the start runs ahead of the share either.
            for (end in 1..out.size) {
                val head = out.take(end)
                val guestsSoFar = head.count(::isGuest)
                val favouritesSoFar = end - guestsSoFar
                assertTrue(
                    "seed $seed: $guestsSoFar guests after $favouritesSoFar favourites",
                    guestsSoFar <= (favouritesSoFar + FAVOURITES_PER_GUEST - 1) / FAVOURITES_PER_GUEST,
                )
            }
        }
    }

    @Test
    fun `the mix opens on a favourite and two guests never play back to back`() {
        val favourites = interleaveByArtist(listOf(artist("A", 30), artist("B", 30)), Random(3))
        val guests = artist("x", 30)

        for (seed in 1..200) {
            val out = dealGuests(favourites, guests, Random(seed))
            assertFalse("seed $seed opened on a guest", isGuest(out.first()))
            for (i in 1 until out.size) {
                assertFalse("seed $seed: ${out[i - 1]} then ${out[i]}", isGuest(out[i - 1]) && isGuest(out[i]))
            }
        }
    }

    @Test
    fun `a guest is not always the fourth song`() {
        val favourites = artist("A", 60)
        val places = (1..40).flatMap { seed ->
            dealGuests(favourites, artist("x", 20), Random(seed)).withIndex().filter { isGuest(it.value) }.map { it.index % 4 }
        }.toSet()

        assertTrue("guests only ever landed at $places of each four", places.size > 1)
    }

    @Test
    fun `fewer than three favourites left over earn no guest`() {
        // Five favourites are one whole three and two over, so one guest, however many wait.
        val out = dealGuests(artist("A", 5), artist("x", 9), Random(1))

        assertEquals(6, out.size)
        assertEquals(1, out.count(::isGuest))
        assertEquals(emptyList<String>(), dealGuests(emptyList(), artist("x", 3), Random(1)))
        assertEquals(artist("A", 2), dealGuests(artist("A", 2), artist("x", 3), Random(1)))
    }

    @Test
    fun `when the guests run out the favourites carry on alone`() {
        val favourites = artist("A", 30)
        val out = dealGuests(favourites, artist("x", 2), Random(1))

        assertEquals(32, out.size)
        assertEquals(favourites, out.filterNot(::isGuest))
        assertEquals(listOf("x1", "x2"), out.filter(::isGuest))
    }

    @Test
    fun `nobody plays twice`() {
        val favourites = interleaveByArtist(listOf(artist("A", 25), artist("B", 25)), Random(6))
        val out = dealGuests(favourites, artist("x", 8) + artist("y", 8), Random(6))

        assertEquals("a song was dealt twice", out.size, out.toSet().size)
    }

    @Test
    fun `with no guests the mix is the favourites as they were`() {
        val favourites = interleaveByArtist(listOf(artist("A", 20), artist("B", 7)), Random(8))

        assertEquals(favourites, dealGuests(favourites, emptyList(), Random(8)))
    }

    @Test
    fun `favourites added at the end leave the mix above them where it was`() {
        // The screen re-deals whenever a song is stored. What was already dealt must not move.
        val before = artist("A", 12)
        val guests = artist("x", 20)

        for (seed in 1..100) {
            val shown = dealGuests(before, guests, Random(seed))
            val grown = dealGuests(before + artist("B", 9), guests, Random(seed))
            assertEquals("seed $seed", shown, grown.take(shown.size))
        }
    }

    // Who is invited

    @Test
    fun `as many similar artists as favourites, and never fewer than three`() {
        assertEquals(0, guestArtistsInvited(0))
        assertEquals(3, guestArtistsInvited(1))
        assertEquals(3, guestArtistsInvited(3))
        assertEquals(10, guestArtistsInvited(10))
    }

    @Test
    fun `the artists the favourites point at most are the ones invited`() {
        val pool = candidates("weak", 6, refs = 1) + candidates("strong", 2, refs = 9) +
            candidates("middle", 3, refs = 4) + candidates("faint", 1, refs = 1) + candidates("also", 2, refs = 5)

        // Three are invited for one favourite: strong (18), middle (12) and also (10), not weak (6).
        val invited = queue(pool, favouriteArtists = 1).map(::artistOf).toSet()

        assertEquals(setOf("strong", "middle", "also"), invited)
    }

    @Test
    fun `a tie between similar artists falls the same way every time`() {
        val pool = candidates("p", 2, refs = 2) + candidates("q", 2, refs = 2) + candidates("r", 2, refs = 2) +
            candidates("s", 2, refs = 2)

        val first = queue(pool, favouriteArtists = 1, seed = 1).map(::artistOf).toSet()
        for (seed in 2..30) {
            assertEquals(first, queue(pool.shuffled(Random(seed)), favouriteArtists = 1, seed = seed).map(::artistOf).toSet())
        }
    }

    @Test
    fun `the guests take turns like the favourites do`() {
        // One similar artist with forty songs known must not hold every guest place.
        val pool = candidates("x", 40, refs = 3) + candidates("y", 4, refs = 3) + candidates("z", 4, refs = 3)
        val out = queue(pool, favouriteArtists = 3)

        assertEquals(setOf("x", "y", "z"), out.take(3).map(::artistOf).toSet())
        for (i in 1 until 8) assertTrue("${out[i - 1]} then ${out[i]}", artistOf(out[i - 1]) != artistOf(out[i]))
    }

    @Test
    fun `a candidate with no artist is left out and one song is queued once`() {
        val pool = listOf(Candidate("x1", 2), Candidate("x1", 2), Candidate("7", 5), Candidate("y1", 1))

        assertEquals(setOf("x1", "y1"), queue(pool, favouriteArtists = 2).toSet())
        assertEquals(2, queue(pool, favouriteArtists = 2).size)
    }

    @Test
    fun `no favourites means nobody is invited`() {
        assertEquals(emptyList<String>(), queue(candidates("x", 5, refs = 3), favouriteArtists = 0))
    }

    // A favourite is never handed over as similar

    private fun strangers(guests: List<String>, favourites: List<String>, artistsOf: (String) -> List<String> = { listOf(artistOf(it)) }) =
        strangersOnly(guests, favourites.toSet(), favourites.map(::artistOf).toSet(), { it }, artistsOf)

    @Test
    fun `a song that is already a favourite is not a guest`() {
        val favourites = artist("A", 3)

        assertEquals(listOf("x1", "x2"), strangers(listOf("x1", "A2", "x2"), favourites))
    }

    @Test
    fun `a song with a favourite artist on it is not a guest, wherever they are billed`() {
        // "x9" is by x and features A: it belongs to the favourites, and the query that feeds them
        // will hand it over there the moment it is known, so here it would play twice.
        val billing = mapOf("x9" to listOf("x", "A"))
        val out = strangers(listOf("x1", "x9", "y1"), artist("A", 3)) { billing[it] ?: listOf(artistOf(it)) }

        assertEquals(listOf("x1", "y1"), out)
    }

    @Test
    fun `a guest listed twice is kept once`() {
        assertEquals(listOf("x1", "y1"), strangers(listOf("x1", "y1", "x1"), artist("A", 2)))
    }

    // Strict

    @Test
    fun `strict is the favourites mix exactly as it was before there were guests`() {
        val flat = artist("A", 30) + artist("B", 4) + artist("C", 9)
        val guests = artist("x", 20)

        for (seed in 1..30) {
            val today = interleaveBy(flat, Random(seed)) { artistOf(it) }
            val strict = favouritesMix(today, guests, strict = true, setOf("A", "B", "C"), Random(seed), { it }) { listOf(artistOf(it)) }
            assertEquals("seed $seed", today, strict)
        }
    }

    @Test
    fun `not strict is the same favourites with strangers dealt in`() {
        val today = interleaveBy(artist("A", 30) + artist("B", 12), Random(5)) { artistOf(it) }
        val guests = listOf("x1", "A7", "y1", "x1", "z1")

        val out = favouritesMix(today, guests, strict = false, setOf("A", "B"), Random(5), { it }) { listOf(artistOf(it)) }

        assertEquals(today, out.filterNot(::isGuest))
        assertEquals(listOf("x1", "y1", "z1"), out.filter(::isGuest))
        assertEquals("A7 was dealt twice", out.size, out.toSet().size)
    }
}
