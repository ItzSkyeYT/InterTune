/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a song goes in the list of everything heard: see [RecognitionHistory.merged]. A song named
 * later, from a listen kept with no connection (issue #48), goes where it was heard.
 */
class HeardOrderTest {

    private fun heard(title: String, at: Long, videoId: String? = null) =
        Heard(title = title, artist = "Artist", videoId = videoId, at = at, checked = true)

    @Test
    fun `a song heard now goes on top`() {
        val list = listOf(heard("B", 2_000), heard("A", 1_000))
        assertEquals(listOf("C", "B", "A"), RecognitionHistory.merged(list, heard("C", 3_000), 200).map { it.title })
    }

    @Test
    fun `a song named later goes where it was heard, not on top`() {
        val list = listOf(heard("C", 3_000), heard("A", 1_000))
        assertEquals(listOf("C", "B", "A"), RecognitionHistory.merged(list, heard("B", 2_000), 200).map { it.title })
    }

    @Test
    fun `the same song heard again since keeps its newer sighting`() {
        val list = listOf(heard("B", 3_000, videoId = "b"), heard("A", 1_000))
        val merged = RecognitionHistory.merged(list, heard("B", 2_000), 200)
        assertEquals(listOf(3_000L, 1_000L), merged.map { it.at })
        assertEquals("b", merged.first().videoId)
    }

    @Test
    fun `a newer sighting of the same song replaces the older`() {
        val list = listOf(heard("B", 2_000), heard("A", 1_000))
        assertEquals(listOf(3_000L, 1_000L), RecognitionHistory.merged(list, heard("B", 3_000), 200).map { it.at })
    }

    /** The engine saves a song as it is named, then again with the recording it chose, at the same moment. */
    @Test
    fun `the same song saved again at the same moment takes the newer save`() {
        val list = listOf(heard("B", 2_000, videoId = "first"), heard("A", 1_000))
        val merged = RecognitionHistory.merged(list, heard("B", 2_000, videoId = "chosen"), 200)
        assertEquals(listOf("chosen", null), merged.map { it.videoId })
    }

    @Test
    fun `past the limit the oldest go`() {
        val list = (1..5).map { heard("S$it", it * 1_000L) }.reversed()
        assertEquals(listOf("S6", "S5", "S4"), RecognitionHistory.merged(list, heard("S6", 6_000), 3).map { it.title })
        // One named later and older than everything kept is not kept at all.
        assertEquals(listOf("S5", "S4", "S3"), RecognitionHistory.merged(list, heard("S0", 0), 3).map { it.title })
    }
}
