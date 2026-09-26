/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.lrclib.models.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The order LRCLIB's two requests are made in, with canned answers in place of the network. */
class LrcLibLyricsProviderTest {

    private fun query(title: String, vararg artists: String, duration: Int) =
        LyricsQuery(id = "x", title = title, artists = artists.toList(), duration = duration)

    private fun track(title: String, artist: String, duration: Double, synced: String? = null, plain: String? = null) =
        Track(id = 1, trackName = title, artistName = artist, duration = duration, plainLyrics = plain, syncedLyrics = synced)

    private val nanana = query("(It Goes Like) Nanana (Edit)", "Peggy Gou", duration = 232)
    private val nananaTimed = track("(It Goes Like) Nanana", "Peggy Gou", 232.0, synced = "[00:01.00]timed")

    @Test
    fun `a failed exact lookup still leaves the search`() = runBlocking {
        // A busy server's 503 and a dropped connection alike; any failure of the exact lookup is a miss.
        for (failure in listOf(IllegalStateException("503 ServerOverloaded"), IOException("connection reset"))) {
            val found = LrcLibLyricsProvider.lookUp(nanana, exact = { _, _, _ -> throw failure }, search = { _, _ -> listOf(nananaTimed) })
            assertEquals("[00:01.00]timed", found.getOrNull())
        }
    }

    @Test
    fun `plain words from the exact lookup do not stop the search for timed ones`() = runBlocking {
        // As for "Samba de Janeiro" at 169 s: the exact entry had plain words only, the search timed ones.
        val q = query("Samba de Janeiro (Radio Edit)", "Bellini", duration = 169)
        val found = LrcLibLyricsProvider.lookUp(
            q,
            exact = { _, _, _ -> track("Samba de Janeiro", "Bellini", 169.0, plain = "plain words") },
            search = { _, _ -> listOf(track("Samba de Janeiro", "Bellini", 170.0, synced = "[00:01.00]timed")) },
        )
        assertEquals("[00:01.00]timed", found.getOrNull())
    }

    @Test
    fun `timed lyrics from the exact lookup end it`() = runBlocking {
        var searches = 0
        val found = LrcLibLyricsProvider.lookUp(nanana, exact = { _, _, _ -> nananaTimed }, search = { _, _ -> searches++; emptyList() })
        assertEquals("[00:01.00]timed", found.getOrNull())
        assertEquals(0, searches)
    }

    @Test
    fun `the exact lookup is only asked with an artist and a length it takes`() = runBlocking {
        for (duration in listOf(-1, 0, 3601, 5400)) {
            var exacts = 0
            var searches = 0
            LrcLibLyricsProvider.lookUp(query("Song", "A", duration = duration), exact = { _, _, _ -> exacts++; null }, search = { _, _ -> searches++; emptyList() })
            assertEquals("length $duration", 0, exacts)
            assertEquals("length $duration", 1, searches)
        }
        var exacts = 0
        LrcLibLyricsProvider.lookUp(query("Song", duration = 200), exact = { _, _, _ -> exacts++; null }, search = { _, _ -> emptyList() })
        assertEquals("no artist", 0, exacts)
        LrcLibLyricsProvider.lookUp(query("Song", "A", duration = 3600), exact = { _, _, _ -> exacts++; null }, search = { _, _ -> emptyList() })
        assertEquals("an hour", 1, exacts)
    }

    @Test
    fun `all the artists are tried, then the first alone, each exact before search`() = runBlocking {
        val asked = mutableListOf<String>()
        val found = LrcLibLyricsProvider.lookUp(
            query("Bailando (Video Edit)", "Paradisio", "Marisa", duration = 230),
            exact = { t, a, d -> asked += "exact $t / $a / $d"; null },
            search = { t, a -> asked += "search $t / $a"; emptyList() },
        )
        assertEquals(
            listOf(
                "exact Bailando / Paradisio, Marisa / 230", "search Bailando / Paradisio, Marisa",
                "exact Bailando / Paradisio / 230", "search Bailando / Paradisio",
            ),
            asked,
        )
        assertEquals("Lyrics unavailable", found.exceptionOrNull()?.message)
    }

    @Test
    fun `a cancelled exact lookup is not taken for a miss`() = runBlocking {
        var searches = 0
        val found = LrcLibLyricsProvider.lookUp(nanana, exact = { _, _, _ -> throw CancellationException("gone") }, search = { _, _ -> searches++; listOf(nananaTimed) })
        assertTrue(found.exceptionOrNull() is CancellationException)
        assertEquals(0, searches)
    }
}
