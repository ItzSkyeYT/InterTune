/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a title admits about the recording, and what it must not be made to admit. */
class SongTagsTest {

    @Test
    fun `the qualifier regions are read`() {
        assertEquals(setOf("slowed", "reverb"), SongTags.of("BRODYAGA FUNK (SLOWED + REVERB)"))
        assertEquals(setOf("sped"), SongTags.of("Wake Up [Sped Up]"))
        assertEquals(setOf("remix"), SongTags.of("Levitating - Maduk Remix"))
        assertEquals(setOf("nightcore"), SongTags.of("Faded （Nightcore）"))
    }

    @Test
    fun `the body of a title is never read, because songs are called all sorts of things`() {
        // The whole reason this only looks inside brackets and after a dash.
        assertEquals(emptySet<String>(), SongTags.of("Live and Let Die"))
        assertEquals(emptySet<String>(), SongTags.of("Slow Down"))
        assertEquals(emptySet<String>(), SongTags.of("The Remix Artist"))
        assertEquals(emptySet<String>(), SongTags.of("Cover Me in Sunshine"))
    }

    @Test
    fun `most music carries no qualifier and that is the right answer`() {
        assertEquals(emptySet<String>(), SongTags.of("Africa"))
        assertEquals(emptySet<String>(), SongTags.of(null))
        assertEquals(emptySet<String>(), SongTags.of("   "))
    }

    @Test
    fun `nightcore is its own thing, not a synonym for sped up`() {
        assertTrue("nightcore" in SongTags.of("Faded (Nightcore)"))
        assertTrue("sped" !in SongTags.of("Faded (Nightcore)"))
    }

    @Test
    fun `overlap is a share of the smaller set, so one tag in common is not one in three`() {
        assertEquals(1.0, SongTags.overlap(setOf("slowed"), setOf("slowed", "reverb")), 1e-9)
        assertEquals(0.0, SongTags.overlap(setOf("slowed"), setOf("live")), 1e-9)
        assertEquals(0.0, SongTags.overlap(emptySet(), setOf("slowed")), 1e-9)
    }
}
