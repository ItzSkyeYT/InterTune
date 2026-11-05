/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The radio the phone makes when YouTube gives none (LocalRadio): what it takes from each list,
 * in which order, and what it never takes.
 */
class LocalRadioTest {

    private fun song(id: String, artist: String = "artist-$id", title: String = "Song $id", liked: Boolean = false, local: Boolean = false) =
        MediaMetadata(
            id = id, title = title, duration = 200, genre = null, liked = liked, isLocal = local,
            artists = listOf(MediaMetadata.Artist(id = "UC-$artist", name = artist)),
        )

    private val seed = song("A", title = "Bye, Summer")

    @Test
    fun `a song from each list in turn, Last-fm's first`() {
        val picked = LocalRadio.pick(
            seed,
            LocalRadio.Sources(
                lastFm = listOf(song("L1"), song("L2")),
                youtube = listOf(song("Y1"), song("Y2")),
                heardWith = listOf(song("H1")),
                listing = listOf(song("I1")),
                byItsArtists = listOf(song("B1")),
                nextHop = listOf(song("N1")),
            ),
            LocalRadio.Rules(),
        )
        assertEquals(listOf("L1", "Y1", "H1", "I1", "B1", "N1", "L2", "Y2"), picked.map { it.id })
    }

    @Test
    fun `never the song itself, a version of it, or a song twice`() {
        val picked = LocalRadio.pick(
            seed,
            LocalRadio.Sources(
                lastFm = listOf(song("A"), song("A2", title = "Bye, Summer (Live)"), song("B")),
                youtube = listOf(song("B"), song("C")),
                // The same song under another id, as the related shelves list them.
                heardWith = listOf(song("C2", artist = "artist-C", title = "Song C")),
            ),
            LocalRadio.Rules(),
        )
        assertEquals(listOf("B", "C"), picked.map { it.id })
    }

    @Test
    fun `nothing the listener keeps out, by song or by artist`() {
        val picked = LocalRadio.pick(
            seed,
            LocalRadio.Sources(lastFm = listOf(song("B"), song("C"), song("D", artist = "Banned Band"), song("E"), song("F"))),
            LocalRadio.Rules(keptOut = setOf("B"), artistsKeptOut = setOf("UC-artist-C", "banned band")),
        )
        assertEquals(listOf("E", "F"), picked.map { it.id })
    }

    @Test
    fun `only what can play now, with YouTube out of reach, what is on the phone`() {
        val onPhone = setOf("C", "E")
        val picked = LocalRadio.pick(
            seed,
            LocalRadio.Sources(youtube = listOf(song("B"), song("C"), song("D"), song("E"))),
            LocalRadio.Rules(plays = { it.id in onPhone }),
        )
        assertEquals(listOf("C", "E"), picked.map { it.id })
    }

    @Test
    fun `with a content switch on, only songs the listener chose, which carry no mark to hold them to`() {
        val picked = LocalRadio.pick(
            seed,
            LocalRadio.Sources(
                lastFm = listOf(song("B"), song("C", liked = true), song("D", local = true)),
                youtube = listOf(song("E").copy(inLibrary = LocalDateTime.of(2026, 10, 1, 12, 0)), song("F")),
            ),
            LocalRadio.Rules(chosenOnly = true),
        )
        assertEquals(listOf("C", "E", "D"), picked.map { it.id })
    }

    @Test
    fun `no artist takes more than a quarter while others are left, and the rest when none are`() {
        val theirs = (1..10).map { song("X$it", artist = "X") }
        val others = listOf(song("O1"), song("O2"), song("O3"))
        val picked = LocalRadio.pick(
            seed,
            LocalRadio.Sources(byItsArtists = theirs, nextHop = others),
            LocalRadio.Rules(),
            size = 12,
        )
        assertEquals(12, picked.size)
        // Three of X's (a quarter of twelve), the three others, and then X's again, as nothing else is left.
        assertEquals(listOf("X1", "O1", "X2", "O2", "X3", "O3", "X4", "X5", "X6", "X7", "X8", "X9"), picked.map { it.id })
        assertEquals(3, LocalRadio.perArtist(12))
        assertEquals(6, LocalRadio.perArtist(25))
    }

    @Test
    fun `a radio is no longer than asked, and nothing to go on makes none`() {
        val many = (1..60).map { song("S$it") }
        assertEquals(LocalRadio.SIZE, LocalRadio.pick(seed, LocalRadio.Sources(youtube = many), LocalRadio.Rules()).size)
        assertTrue(LocalRadio.pick(seed, LocalRadio.Sources(), LocalRadio.Rules()).isEmpty())
    }
}
