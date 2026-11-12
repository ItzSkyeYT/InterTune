/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Songs like the ones skipped (Unreleased.ADAPTIVE_SIMILAR, 9 Nov 2026): a song is like a skipped one when it
 * stands closer to that song's related list than to any list of a song kept or liked in the run, closeness
 * being its place on the list, as YouTube puts the closest first.
 */
class SkipSimilarTest {

    @Test
    fun `closeness falls with the place on a list, half at the tenth, nothing off it`() {
        assertEquals(1.0, SkipSimilar.closeness(0), 1e-9)
        assertEquals(0.5, SkipSimilar.closeness(10), 1e-9)
        assertTrue(SkipSimilar.closeness(3) > SkipSimilar.closeness(4))
        assertEquals(0.0, SkipSimilar.closeness(null), 1e-9)
        assertEquals(0.0, SkipSimilar.closeness(-1), 1e-9)
    }

    @Test
    fun `with nothing kept yet, everything on the list of the song just skipped is like it, as before`() {
        val read = SkipSimilar.read(skipped = listOf(listOf("a", "b", "c")), kept = emptyList(), lastJustSkipped = true)
        assertEquals(setOf("a", "b", "c"), read.likeLast)
        assertEquals(emptySet<String>(), read.likeTwo)
    }

    @Test
    fun `a song closer to what was kept than to the song skipped stays`() {
        // "b" is third on the skipped song's list but first on a kept song's: like what is kept, it stays.
        // "c" is first on the skipped song's list and only twentieth on the kept one's: like the skip.
        val kept = listOf(listOf("b") + (1..18).map { "k$it" } + "c")
        val read = SkipSimilar.read(listOf(listOf("c", "x", "b")), kept, lastJustSkipped = true)
        assertTrue("c" in read.likeLast)
        assertTrue("x" in read.likeLast)
        assertTrue("b" !in read.likeLast)
    }

    @Test
    fun `at the same place on both lists, a song is not closer to the skip`() {
        val read = SkipSimilar.read(listOf(listOf("a", "s")), listOf(listOf("k", "s")), lastJustSkipped = true)
        assertTrue("s" !in read.likeLast)
        assertTrue("a" in read.likeLast)
    }

    @Test
    fun `a song like two songs skipped is read as such, whichever was skipped last`() {
        val newest = listOf("n1", "shared", "n2")
        val older = listOf("o1", "o2", "shared")
        val read = SkipSimilar.read(listOf(newest, older), kept = emptyList(), lastJustSkipped = true)
        assertEquals(setOf("shared"), read.likeTwo)
        assertEquals(setOf("n1", "shared", "n2"), read.likeLast)
    }

    @Test
    fun `a song kept close is never like two skips, however many list it`() {
        val read = SkipSimilar.read(listOf(listOf("x", "s"), listOf("s", "y")), kept = listOf(listOf("s")), lastJustSkipped = true)
        assertTrue("s" !in read.likeTwo)
        assertTrue("s" !in read.likeLast)
    }

    @Test
    fun `away from a skip, nothing is like the last one, but two skips still count`() {
        val read = SkipSimilar.read(listOf(listOf("a", "shared"), listOf("shared", "b")), kept = emptyList(), lastJustSkipped = false)
        assertEquals(emptySet<String>(), read.likeLast)
        assertEquals(setOf("shared"), read.likeTwo)
    }

    @Test
    fun `a song twice on one list counts once, at its first place`() {
        val read = SkipSimilar.read(listOf(listOf("a", "b", "a")), kept = emptyList(), lastJustSkipped = false)
        assertEquals(emptySet<String>(), read.likeTwo)
    }

    @Test
    fun `no skipped list, nothing like a skip`() {
        val read = SkipSimilar.read(emptyList(), listOf(listOf("k")), lastJustSkipped = true)
        assertEquals(emptySet<String>(), read.likeLast)
        assertEquals(emptySet<String>(), read.likeTwo)
    }
}
