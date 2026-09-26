/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The order the providers are asked in, when the lookup stops, and what it waits for. */
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

    @Test
    fun `a length the player has is used at once`() = runBlocking {
        assertEquals(230, LyricsLookup.awaitLength(230, flow { fail("the database was asked") }, waitMs = 1_000))
    }

    @Test
    fun `a song tapped in search waits for the length the stream brings`() = runBlocking {
        // The row starts at -1 and recoverSong writes the real length once the stream is resolved.
        val lengths = flow { emit(-1); delay(30); emit(0); delay(30); emit(230); awaitCancellation() }
        assertEquals(230, LyricsLookup.awaitLength(-1, lengths, waitMs = 5_000))
    }

    @Test
    fun `no length within the wait is -1, and so is a source that ends without one`() = runBlocking {
        val start = System.nanoTime()
        assertEquals(-1, LyricsLookup.awaitLength(-1, flow { emit(-1); awaitCancellation() }, waitMs = 100))
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 100)
        assertEquals(-1, LyricsLookup.awaitLength(-1, flowOf(-1, 0), waitMs = 5_000))
    }

    @Test
    fun `a second copy of the song playing does not restart its lookup`() = runBlocking {
        fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 200, genre = null)
        val first = song("a")
        val copy = song("a")
        // The random field every MediaMetadata carries makes them unequal, as a rebuilt queue's copy is.
        assertNotEquals(first, copy)

        val metadata = MutableStateFlow<MediaMetadata?>(first)
        val seen = Channel<MediaMetadata?>(Channel.UNLIMITED)
        val results = Channel<String>(Channel.UNLIMITED)
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val collector = launch {
            LyricsLookup.bySong(metadata.onEach { seen.send(it) }) { song ->
                started += song.id
                try {
                    if (song.id == "a") release.await()
                } catch (e: CancellationException) {
                    cancelled += song.id
                    throw e
                }
                "lyrics of ${song.id}"
            }.collect { results.send(it) }
        }
        assertEquals(first, seen.receive())
        metadata.value = copy
        assertEquals(copy, seen.receive())
        yield()
        release.complete(Unit)
        assertEquals("lyrics of a", results.receive())
        metadata.value = song("b")
        assertEquals("lyrics of b", results.receive())
        collector.cancel()

        assertEquals(listOf("a", "b"), started)
        assertEquals(emptyList<String>(), cancelled)
    }
}
