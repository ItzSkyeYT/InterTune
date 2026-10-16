/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How much of what someone plays is new to them, and the new cards that makes in a row. */
class MixShareTest {
    private val now = 1_789_135_200_000L
    private val minute = 60_000L

    private fun input(listens: List<ListenRow>, songs: Collection<String>, links: List<VersionLink> = emptyList()) =
        EngineInput(now, songs.associateWith { SongRow(it, it, "art", "Artist") }, listens, emptyList(), links)

    private fun listen(id: String, i: Int, origin: PlayOrigin = PlayOrigin.SEARCH, ratio: Double = 1.0): ListenRow {
        val start = now - (1000 - i) * 5 * minute
        val played = (200_000 * ratio).toLong()
        return ListenRow(id, start, start + played, played, 200_000, EndReason.ENDED, origin.code, 0, i.toLong(), 0)
    }

    private fun share(input: EngineInput) = MixShare.of(input, VersionGroups(input.songs.values, input.versionLinks))

    @Test
    fun `a new install starts at the prior`() {
        val empty = input(emptyList(), emptyList())
        assertEquals(EngineParams.DEFAULT.mixPrior, share(empty), 1e-9)
        assertEquals(5, MixShare.newCards(share(empty)))
    }

    @Test
    fun `someone who replays the same songs gets the floor, someone who explores the ceiling`() {
        val replays = input((0 until 400).map { listen("s${it % 10}", it) }, (0 until 10).map { "s$it" })
        assertTrue(share(replays) < 0.1)
        assertEquals(EngineParams.DEFAULT.mixNewMin, MixShare.newCards(share(replays)))
        val explores = input((0 until 400).map { listen("s$it", it) }, (0 until 400).map { "s$it" })
        assertTrue(share(explores) > 0.9)
        assertEquals(EngineParams.DEFAULT.mixNewMax, MixShare.newCards(share(explores)))
    }

    @Test
    fun `plays started from the engine's own rows do not count, and what they made known stays known`() {
        // Forty songs first heard from Quick picks, then each played again from search: forty
        // counted listens, none of them new, so only the prior's seven and a half new are left.
        val listens = (0 until 40).map { listen("s$it", it, PlayOrigin.QUICK_PICKS) } + (0 until 40).map { listen("s$it", 40 + it) }
        assertEquals(0.25 * 30 / 70, share(input(listens, (0 until 40).map { "s$it" })), 1e-9)
    }

    @Test
    fun `the row's own cards count per card shown, so playing its new cards brings more of them`() {
        // Outside the row: 300 plays of ten songs, new only the first time each was heard.
        val outside = (0 until 300).map { listen("s${it % 10}", it) }
        val base = input(outside, (0 until 10).map { "s$it" } + (0 until 60).map { "n$it" })
        val before = share(base)
        // On the row in the last fortnight: forty new cards and forty familiar ones shown, half of
        // each played and heard well: new cards played as often as familiar ones, so half and half.
        val seenAt = now - 3 * 86_400_000L
        val seen = (0 until 20).map { SeenCard("n$it", seenAt) } + (0 until 20).map { SeenCard("s${it % 10}", seenAt) }
        val played = (0 until 20).map { listen("n${20 + it}", 900 + it, PlayOrigin.QUICK_PICKS) } +
            (0 until 20).map { listen("s${it % 10}", 950 + it, PlayOrigin.QUICK_PICKS) }
        val withRow = base.copy(listens = outside + played, seen = seen)
        assertEquals((10 + 40 * 0.5 + 7.5) / (300 + 40 + 30), share(withRow), 1e-9)
        assertTrue(share(withRow) > before)
        // The same plays with nothing seen beside them say nothing: a row's taps alone cannot tell
        // how often each kind was passed over.
        assertEquals(before, share(base.copy(listens = outside + played)), 1e-9)
    }

    @Test
    fun `a short listen is not counted, and another version of a song heard well is not new`() {
        val listens = listOf(listen("a", 0), listen("a-live", 1), listen("b", 2, ratio = 0.05))
        // Counted: a, new, and its live take, the same song: one new of two.
        assertEquals((1 + 7.5) / (2 + 30), share(input(listens, listOf("a", "a-live", "b"), links = listOf(VersionLink("a", "a-live")))), 1e-9)
    }
}
