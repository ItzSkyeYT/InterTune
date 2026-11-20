/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.engine.SimilarMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ListenBrainz's labs API as the 0.14 engine asks it (ListenBrainzLabs). The answers are real, from the 18 Nov 2026
 * trial (experiments/recs-trial-1118/cache), cut to their first rows.
 */
class ListenBrainzLabsTest {
    private val lookup = """[{"index": 0, "artist_credit_arg": "New Order", "recording_arg": "Blue Monday", "artist_credit_name": "New Order", "release_name": "Power, Corruption & Lies", "recording_name": "Blue Monday", "artist_credit_id": 846, "artist_mbids": ["f1106b17-dcbb-45f6-b938-199ccfab50cc"], "release_mbid": "f2dd4d75-adcd-4347-93f3-4ea35f2b3b35", "recording_mbid": "9aa2be7b-8fb0-4187-bce4-9bb4e84815fc"}]"""

    private val similar = """[{"recording_mbid": "41746834-4a27-42f5-b651-61ef0ca59549", "recording_name": "Love Will Tear Us Apart", "artist_credit_name": "Joy Division", "artist_credit_mbids": null, "release_name": "Closer", "release_mbid": "97d8bb43-f46f-3460-92dc-d6acdb3f1279", "caa_id": 2568714507, "caa_release_mbid": "ee50be9b-85c9-3f3c-a851-e198731ae2ff", "score": 1065, "reference_mbid": "9aa2be7b-8fb0-4187-bce4-9bb4e84815fc"}, {"recording_mbid": "8caee4aa-bc6e-454d-b8ab-16695a398c75", "recording_name": "Enjoy the Silence", "artist_credit_name": "Depeche Mode", "artist_credit_mbids": null, "release_name": "Violator", "release_mbid": "f534d67f-8da8-485f-9758-f92edab6d4ee", "caa_id": 18529866710, "caa_release_mbid": "5454efa9-445a-4b73-8d16-e9fdbf022119", "score": 707, "reference_mbid": "9aa2be7b-8fb0-4187-bce4-9bb4e84815fc"}, {"recording_mbid": "ef4c6855-949e-4e22-b41e-8e0a2d372d5f", "recording_name": "Tainted Love", "artist_credit_name": "Soft Cell", "artist_credit_mbids": null, "release_name": "Non-Stop Erotic Cabaret", "release_mbid": "1acaa870-6e0c-4b6e-9e91-fdec4e5ea4b1", "caa_id": 1031647403, "caa_release_mbid": "c3367d3a-2f6c-48d1-95c5-c1ee7a49c479", "score": 608, "reference_mbid": "9aa2be7b-8fb0-4187-bce4-9bb4e84815fc"}]"""

    @Test
    fun `a song is named by artist and title, its neighbours asked for by id with the measured algorithm`() {
        val named = LbLabs.lookupAddress("Simon & Garfunkel", "The Sound of Silence")
        assertEquals("https://labs.api.listenbrainz.org/acr-lookup/json?artist_credit_name=Simon%20%26%20Garfunkel&recording_name=The%20Sound%20of%20Silence", named.toString())
        val near = LbLabs.similarAddress("9aa2be7b-8fb0-4187-bce4-9bb4e84815fc")
        assertEquals("labs.api.listenbrainz.org", near.host)
        assertEquals("9aa2be7b-8fb0-4187-bce4-9bb4e84815fc", near.queryParameter("recording_mbids"))
        assertEquals("the 7,500-day algorithm the 18 Nov trial measured", LbLabs.ALGORITHM, near.queryParameter("algorithm"))
        assertEquals(setOf("recording_mbids", "algorithm"), near.queryParameterNames)
    }

    @Test
    fun `the lookup's first match gives the id, and nothing else counts as one`() {
        assertEquals("9aa2be7b-8fb0-4187-bce4-9bb4e84815fc", LbLabs.mbidOf(lookup))
        assertNull("no match", LbLabs.mbidOf("[]"))
        assertNull("not an id", LbLabs.mbidOf("""[{"recording_mbid": "blue monday"}]"""))
        assertNull("labs changed", LbLabs.mbidOf("""{"error": "gone"}"""))
        assertNull("not JSON at all", LbLabs.mbidOf("<html>"))
    }

    @Test
    fun `the neighbours come in ListenBrainz's order, as title and artist for SimilarMatch`() {
        assertEquals(
            listOf(
                SimilarMatch.Neighbour("Love Will Tear Us Apart", "Joy Division"),
                SimilarMatch.Neighbour("Enjoy the Silence", "Depeche Mode"),
                SimilarMatch.Neighbour("Tainted Love", "Soft Cell"),
            ),
            LbLabs.neighboursOf(similar),
        )
        assertEquals("knows none: an answer", emptyList<SimilarMatch.Neighbour>(), LbLabs.neighboursOf("[]"))
        assertEquals("a row with no artist is left out", emptyList<SimilarMatch.Neighbour>(), LbLabs.neighboursOf("""[{"recording_name": "X"}]"""))
        assertNull("not a list: labs changed, no answer", LbLabs.neighboursOf("""{"error": "gone"}"""))
    }

    @Test
    fun `a second between requests, and the rest of the window when ListenBrainz says it is the last`() {
        assertEquals(1_000L, LbLabs.waitAfter(null, null))
        assertEquals(1_000L, LbLabs.waitAfter(20, 7))
        assertEquals(8_000L, LbLabs.waitAfter(1, 7))
        assertEquals(8_000L, LbLabs.waitAfter(0, 7))
        assertEquals(1_000L, LbLabs.waitAfter(0, 0))
    }

    @Test
    fun `the requests carry the app's name and nothing about the listener`() {
        assertEquals("InterTune/0.14 ( https://github.com/ItzSkyeYT/InterTune )", LbLabs.userAgent("0.14"))
        val code = File("src/main/java/com/dd3boh/outertune/utils/ListenBrainzLabs.kt").readText()
        assertFalse("no token", "Authorization" in code)
        assertTrue("one way out, through the gate", Regex("""client\.newCall\(""").findAll(code).count() == 1)
    }
}
