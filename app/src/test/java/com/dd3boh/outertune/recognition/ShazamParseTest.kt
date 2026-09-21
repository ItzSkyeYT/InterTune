/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply is the part of this feature nobody controls.
 *
 * Shazam's shape drifts, so the parser is written to survive that and these say what surviving
 * means: a track without the fields we hope for is still a match, an absent track is an answer
 * rather than a failure, and the YouTube id is found wherever it happens to be sitting.
 */
class ShazamParseTest {

    @Test
    fun `a full match reads every field`() {
        val result = ShazamClient.parse(
            """
            {"track":{"key":"40333609","title":"Bohemian Rhapsody","subtitle":"Queen",
              "isrc":"GBUM71029604",
              "images":{"coverart":"https://img/small.jpg","coverarthq":"https://img/big.jpg"},
              "hub":{"options":[{"actions":[{"uri":"https://www.youtube.com/watch?v=fJ9rUzIMcZQ"}]}]}}}
            """.trimIndent()
        )
        val match = result as RecognitionResult.Match
        assertEquals("Bohemian Rhapsody", match.title)
        assertEquals("Queen", match.artist)
        assertEquals("GBUM71029604", match.isrc)
        assertEquals("40333609", match.key)
        // The high quality one wins when both are offered.
        assertEquals("https://img/big.jpg", match.artworkUrl)
        assertEquals("fJ9rUzIMcZQ", match.youtubeId)
    }

    @Test
    fun `the youtube id is found wherever shazam puts it`() {
        // A short link, nested nowhere near hub.options. A path-based parser misses this.
        val match = ShazamClient.parse(
            """{"track":{"title":"T","subtitle":"A","sections":[{"youtubeurl":"https://youtu.be/dQw4w9WgXcQ"}]}}"""
        ) as RecognitionResult.Match
        assertEquals("dQw4w9WgXcQ", match.youtubeId)
    }

    @Test
    fun `a track with no youtube link still matches`() {
        val match = ShazamClient.parse(
            """{"track":{"title":"Obscure","subtitle":"Nobody"}}"""
        ) as RecognitionResult.Match
        assertEquals("Obscure", match.title)
        assertNull(match.youtubeId)
        assertNull(match.artworkUrl)
    }

    @Test
    fun `falls back to the small cover when there is no large one`() {
        val match = ShazamClient.parse(
            """{"track":{"title":"T","subtitle":"A","images":{"coverart":"https://img/small.jpg"}}}"""
        ) as RecognitionResult.Match
        assertEquals("https://img/small.jpg", match.artworkUrl)
    }

    @Test
    fun `no track is not a failure`() {
        assertEquals(RecognitionResult.NoMatch, ShazamClient.parse("""{"matches":[]}"""))
    }

    @Test
    fun `a track without a title is not a match`() {
        assertEquals(RecognitionResult.NoMatch, ShazamClient.parse("""{"track":{"key":"1"}}"""))
    }

    @Test
    fun `an unreadable reply is a failure, not a missing song`() {
        assertTrue(ShazamClient.parse("<html>gateway timeout</html>") is RecognitionResult.Failed)
    }

    @Test
    fun `a blank field counts as absent rather than empty`() {
        val match = ShazamClient.parse(
            """{"track":{"title":"T","subtitle":"","isrc":""}}"""
        ) as RecognitionResult.Match
        assertEquals("", match.artist)
        assertNull(match.isrc)
    }
}
