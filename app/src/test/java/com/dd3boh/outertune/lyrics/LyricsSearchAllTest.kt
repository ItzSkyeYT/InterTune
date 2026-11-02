/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * His report of 1 Nov 2026: "Search lyrics" said "Lyrics not found" for Get Lucky while its lyrics
 * were on the screen, and "it happens quite often". The search asked the sources one after another
 * and the first that failed ended it: LRCLIB answered 503 at once for "Pharrell Williams, Daft Punk,
 * Nile Rodgers" (experiments/bugs/lyrics-search/probe_lrclib.py), so the others were never asked.
 * Every source is now asked on its own, and the typed artists are tried one by one as well.
 */
class LyricsSearchAllTest {
    private class Fake(
        override val name: String,
        val answer: suspend (LyricsQuery, (String) -> Unit) -> Unit,
    ) : LyricsProvider {
        override fun isEnabled(context: Context) = true
        override suspend fun getLyrics(query: LyricsQuery): Result<String> = Result.failure(IllegalStateException("not used"))
        override suspend fun getAllLyrics(query: LyricsQuery, callback: (String) -> Unit) = answer(query, callback)
    }

    private val getLucky = LyricsQuery.typed("id", "Get Lucky", "Pharrell Williams, Daft Punk, Nile Rodgers", null, 248)

    @Test
    fun `a source that fails no longer ends the search for the others`() = runBlocking {
        val found = mutableListOf<String>()
        val failed = mutableListOf<String>()
        LyricsLookup.all(
            listOf(
                Fake("LRCLIB") { _, _ -> throw IOException("HTTP 503") },
                Fake("KuGou") { _, give -> give("[00:01.00]We've come too far") },
                Fake("YouTube Music") { _, give -> delay(50); give("We've come too far") },
            ),
            getLucky,
            onFailure = { provider, _ -> synchronized(failed) { failed += provider.name } },
        ) { provider, _ -> synchronized(found) { found += provider.name } }
        assertEquals(listOf("LRCLIB"), failed)
        assertEquals(setOf("KuGou", "YouTube Music"), found.toSet())
    }

    @Test
    fun `the same words from two sources are offered once, and blank answers not at all`() = runBlocking {
        val found = mutableListOf<String>()
        LyricsLookup.all(
            listOf(
                Fake("A") { _, give -> give("[00:01.00]line"); give("  ") },
                Fake("B") { _, give -> give("[00:01.00]line\n") },
            ),
            getLucky,
            onFailure = { _, _ -> },
        ) { _, text -> synchronized(found) { found += text } }
        assertEquals(1, found.size)
    }

    @Test
    fun `stopping the search is not taken for a source failing`() = runBlocking {
        val failed = mutableListOf<String>()
        val search = launch {
            LyricsLookup.all(listOf(Fake("slow") { _, _ -> delay(10_000) }), getLucky, onFailure = { p, _ -> failed += p.name }) { _, _ -> }
        }
        delay(50)
        search.cancel()
        search.join()
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `the typed artists are tried together and then one by one`() {
        assertEquals(listOf("Pharrell Williams", "Daft Punk", "Nile Rodgers"), getLucky.artists)
        assertEquals(
            listOf("Pharrell Williams, Daft Punk, Nile Rodgers", "Pharrell Williams", "Daft Punk", "Nile Rodgers"),
            getLucky.searchArtists,
        )
        // Split only where the app joins artists: a name with "&" in it stays whole.
        assertEquals(listOf("Simon & Garfunkel"), LyricsQuery.typed("id", "The Boxer", "Simon & Garfunkel", null, -1).artists)
        assertEquals("nothing typed: the title alone", listOf(""), LyricsQuery.typed("id", "x", "  ", null, -1).searchArtists)
    }

    @Test
    fun `the lyrics search goes through all, with the song's real length, and keeps no empty answer`() {
        val helper = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsHelper.kt").readText()
        val search = helper.substringAfter("suspend fun getAllLyrics(").substringBefore("\n    }\n")
        assertTrue(search.contains("LyricsLookup.all("))
        assertTrue(search.contains("LyricsLookup.awaitLength("))
        assertTrue(search.contains("if (found.isNotEmpty()) cache.put(cacheKey, found.sortedBy { it.rank })"))
        assertFalse("the old walk, one source after another", search.contains("lyricsProviders().forEach"))
    }

    @Test
    fun `LRCLIB and KuGou try each spelling of the artists, a failing one included`() {
        val lrclib = File("src/main/java/com/dd3boh/outertune/lyrics/LrcLibLyricsProvider.kt").readText()
        val kugou = File("src/main/java/com/dd3boh/outertune/lyrics/KuGouLyricsProvider.kt").readText()
        assertTrue(lrclib.contains("for (artist in query.searchArtists + \"\")"))
        assertTrue(kugou.contains("for (artist in query.searchArtists)"))
        for (source in listOf(lrclib, kugou)) assertTrue(source.contains("} catch (e: CancellationException) {\n                throw e"))
    }
}
