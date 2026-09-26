/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Two screens asking for the same song's lyrics at once share one lookup. */
class SharedLookupTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `two callers at once share one run`() = runBlocking {
        val lookups = SharedLookup<String, String>(scope)
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val block: suspend () -> String = { runs.incrementAndGet(); gate.await(); "lyrics" }
        val first = async(Dispatchers.Default) { lookups.get("song", block) }
        val second = async(Dispatchers.Default) { lookups.get("song", block) }
        while (lookups.waiting("song") < 2) kotlinx.coroutines.delay(5)
        gate.complete(Unit)
        assertEquals("lyrics", first.await())
        assertEquals("lyrics", second.await())
        assertEquals(1, runs.get())
    }

    @Test
    fun `different songs run separately`() = runBlocking {
        val lookups = SharedLookup<String, String>(scope)
        assertEquals("a!", lookups.get("a") { "a!" })
        assertEquals("b!", lookups.get("b") { "b!" })
    }

    @Test
    fun `one caller leaving does not stop the run for the other`() = runBlocking {
        val lookups = SharedLookup<String, String>(scope)
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val block: suspend () -> String = { runs.incrementAndGet(); gate.await(); "lyrics" }
        val leaving = async(Dispatchers.Default) { lookups.get("song", block) }
        val staying = async(Dispatchers.Default) { lookups.get("song", block) }
        while (lookups.waiting("song") < 2) kotlinx.coroutines.delay(5)
        leaving.cancel()
        while (lookups.waiting("song") > 1) kotlinx.coroutines.delay(5)
        gate.complete(Unit)
        assertEquals("lyrics", staying.await())
        assertEquals(1, runs.get())
    }

    @Test
    fun `the run is cancelled once nobody is waiting for it`() = runBlocking {
        val lookups = SharedLookup<String, String>(scope)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val caller = async(Dispatchers.Default) {
            lookups.get("song") {
                try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        started.await()
        caller.cancel()
        withTimeout(2_000) { cancelled.await() }
        assertEquals(0, lookups.waiting("song"))
    }

    @Test
    fun `a finished run is not reused, so the next call looks again`() = runBlocking {
        val lookups = SharedLookup<String, Int>(scope)
        val runs = AtomicInteger()
        assertEquals(1, lookups.get("song") { runs.incrementAndGet() })
        assertEquals(2, lookups.get("song") { runs.incrementAndGet() })
    }

    @Test
    fun `a failure reaches the caller and the next call tries again`() = runBlocking {
        val lookups = SharedLookup<String, String>(scope)
        val failed = runCatching { lookups.get("song") { error("offline") } }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertEquals("fine", lookups.get("song") { "fine" })
    }
}
