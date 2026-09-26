/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order the providers are asked in, and when the lookup stops. */
class LyricsLookupTest {

    private val query = LyricsQuery(id = "x", title = "Song", artists = listOf("A"), duration = 200)

    private class Fake(
        override val name: String,
        override val offersSynced: Boolean = true,
        val answer: suspend () -> Result<String>,
    ) : LyricsProvider {
        var calls = 0
        override fun isEnabled(context: Context) = true
        override suspend fun getLyrics(query: LyricsQuery): Result<String> {
            calls++
            return answer()
        }
    }

    private fun failing(name: String) = Fake(name) { Result.failure(IllegalStateException("Lyrics unavailable")) }

    @Test
    fun `every provider is tried until one has synced lyrics`() = runBlocking {
        val a = failing("a"); val b = failing("b")
        val c = Fake("c") { Result.success("[00:01.00]found") }
        val d = Fake("d") { Result.success("[00:01.00]never asked") }
        val failures = mutableListOf<String>()
        val found = LyricsLookup.firstFound(listOf(a, b, c, d), query) { p, _ -> failures += p.name }
        assertEquals("[00:01.00]found", found)
        assertEquals(listOf("a", "b"), failures)
        assertEquals(0, d.calls)
    }

    @Test
    fun `plain words do not stop the search for synced ones`() = runBlocking {
        val a = Fake("a") { Result.success("plain words") }
        val b = Fake("b") { Result.success("[00:01.00]timed") }
        assertEquals("[00:01.00]timed", LyricsLookup.firstFound(listOf(a, b), query) { _, _ -> })
    }

    @Test
    fun `plain words are kept when nobody has synced ones, and a plain-only provider is not asked again`() = runBlocking {
        val a = Fake("a") { Result.success("plain words") }
        val b = failing("b")
        val c = Fake("c", offersSynced = false) { Result.success("other plain words") }
        assertEquals("plain words", LyricsLookup.firstFound(listOf(a, b, c), query) { _, _ -> })
        assertEquals(1, b.calls)
        assertEquals(0, c.calls)
    }

    @Test
    fun `nothing anywhere is null, with each failure reported once`() = runBlocking {
        val providers = listOf(failing("a"), failing("b"), Fake("c") { Result.success("  ") })
        val failures = mutableListOf<String>()
        assertNull(LyricsLookup.firstFound(providers, query) { p, _ -> failures += p.name })
        assertEquals(listOf("a", "b", "c"), failures)
    }

    @Test
    fun `a provider that throws is a failure, not the end of the lookup`() = runBlocking {
        val a = Fake("a") { throw IllegalStateException("boom") }
        val b = Fake("b") { Result.success("[00:01.00]found") }
        assertEquals("[00:01.00]found", LyricsLookup.firstFound(listOf(a, b), query) { _, _ -> })
    }

    @Test
    fun `a cancelled lookup stops, instead of reporting every later provider as a failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        // Like the real providers, it wraps its request in runCatching, which also catches the
        // cancellation and hands it back as a failure.
        val a = Fake("a") { runCatching { started.complete(Unit); never.await(); "unreachable" } }
        val b = Fake("b") { Result.success("[00:01.00]should not be asked") }
        val failures = mutableListOf<String>()
        val lookup = async(Dispatchers.Default) { LyricsLookup.firstFound(listOf(a, b), query) { p, _ -> failures += p.name } }
        started.await()
        lookup.cancel()
        val outcome = runCatching { lookup.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        yield()
        assertEquals(0, b.calls)
        assertEquals(emptyList<String>(), failures)
    }
}
