/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a mood chip is allowed to assume before anybody has taught it anything. */
class ChipPriorTest {

    @Test
    fun `chill wants the slow end and not the fast one`() {
        assertTrue(ChipPrior.fit(ContextChip.CHILL, SongTags.of("X (Slowed + Reverb)")) > 0.0)
        assertTrue(ChipPrior.fit(ContextChip.CHILL, SongTags.of("X (Acoustic)")) > 0.0)
        assertTrue(ChipPrior.fit(ContextChip.CHILL, SongTags.of("X (Nightcore)")) < 0.0)
        assertTrue(ChipPrior.fit(ContextChip.CHILL, SongTags.of("X (Sped Up)")) < 0.0)
    }

    @Test
    fun `party wants the opposite of chill`() {
        assertTrue(ChipPrior.fit(ContextChip.PARTY, SongTags.of("X (Nightcore)")) > 0.0)
        assertTrue(ChipPrior.fit(ContextChip.PARTY, SongTags.of("X (Bass Boosted)")) > 0.0)
        assertTrue(ChipPrior.fit(ContextChip.PARTY, SongTags.of("X (Slowed)")) < 0.0)
    }

    @Test
    fun `an ordinary title is not evidence against anything`() {
        // Most music carries no qualifier. Marking it down would hand the row to the fraction of
        // the library that happens to be labelled, which is not what a mood means.
        for (chip in listOf(ContextChip.CHILL, ContextChip.PARTY, ContextChip.FOCUS)) {
            assertEquals(0.0, ChipPrior.fit(chip, SongTags.of("Just A Song")), 1e-9)
            assertEquals(0.0, ChipPrior.fit(chip, emptySet()), 1e-9)
        }
    }

    @Test
    fun `the chips the prior has no opinion about are left alone`() {
        assertEquals(0.0, ChipPrior.fit(ContextChip.AUTO, SongTags.of("X (Slowed)")), 1e-9)
        assertEquals(0.0, ChipPrior.fit(ContextChip.DISCOVER, SongTags.of("X (Slowed)")), 1e-9)
        assertEquals(0.0, ChipPrior.fit(ContextChip.FAVOURITES, SongTags.of("X (Slowed)")), 1e-9)
    }

    @Test
    fun `the prior is everything at the start and gone once the chip has learned`() {
        assertEquals(1.0, ChipPrior.weight(0), 1e-9)
        assertEquals(0.0, ChipPrior.weight(ContextChip.MIN_TAGGED), 1e-9)
        assertEquals(0.0, ChipPrior.weight(ContextChip.MIN_TAGGED + 50), 1e-9)
        // and it comes down monotonically in between
        var previous = 1.0
        for (n in 1 until ContextChip.MIN_TAGGED) {
            val w = ChipPrior.weight(n)
            assertTrue("weight must not rise at $n", w < previous)
            assertTrue(w in 0.0..1.0)
            previous = w
        }
    }
}
