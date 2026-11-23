/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song with no picture gets a cover the Recap makes (his pick for listeners of their own files,
 * 22 Nov 2026): after its own picture and its folder's, the same cover for the same title every time.
 */
class RecapMadeCoverTest {
    /** Ten units a letter and five a space, so the lines are easy to reckon. */
    private val measure: (String) -> Float = { s -> s.sumOf { if (it == ' ') 5.0 else 10.0 }.toFloat() }

    @Test
    fun `a title wraps by whole words into three lines at most, or asks for a smaller size`() {
        assertEquals(listOf("Hong", "Kong"), MadeCoverPlan.lines("Hong Kong", 60f, measure))
        assertEquals(listOf("Hong Kong"), MadeCoverPlan.lines("Hong Kong", 200f, measure))
        assertEquals("as many words as fit, line after line", listOf("A Song With", "A Long", "Title"), MadeCoverPlan.lines("A Song With A Long Title", 100f, measure))
        assertNull("four lines", MadeCoverPlan.lines("one two three four", 40f, measure))
        assertNull("a word wider than the cover", MadeCoverPlan.lines("Supercalifragilistic", 100f, measure))
        assertNull(MadeCoverPlan.lines("   ", 100f, measure))
    }

    @Test
    fun `the same title has the same paper and shape every time, and a letter for small covers`() {
        val papers = 11
        assertEquals(MadeCoverPlan.paper("WARP", papers), MadeCoverPlan.paper(" WARP ", papers))
        assertEquals(MadeCoverPlan.shape("Aria Math"), MadeCoverPlan.shape("Aria Math"))
        assertTrue((0 until 40).map { MadeCoverPlan.paper("song $it", papers) }.toSet().size > 5)
        assertEquals("A", MadeCoverPlan.letter("addiction (Slowed)"))
        assertEquals("7", MadeCoverPlan.letter("(7 rings)"))
        assertEquals("\u266A", MadeCoverPlan.letter("..."))
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `files and songs with no picture's address get it, after their own picture and their folder's`() {
        val reader = File(sources, "recap/RecapReader.kt").readText()
        assertTrue("cover = if (song.song.isLocal || song.song.thumbnailUrl == null) {\n            RecapMadeCover(" in reader)
        val fetcher = File(sources, "ui/screens/recap/RecapCoverFetcher.kt").readText()
        assertTrue("embedded(path, px) ?: LocalCovers.of(context, path, px)" in fetcher)
        assertTrue("(own ?: draw(data, px)).asImage()" in fetcher)
        val app = File(sources, "App.kt").readText()
        assertEquals("both image loaders, the cache on and off", 2, Regex("""add\(RecapCoverFetcher\.Factory\(this@App\)\)\n\s+add\(RecapMadeCoverKeyer\(\)\)""").findAll(app).count())
    }
}
