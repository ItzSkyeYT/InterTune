/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * One network step with a deadline and a second try on a fresh connection. Real time, in tens of
 * milliseconds: what is tested is which attempt answers and when the step gives up.
 */
class HedgeTest {
    private val started = Collections.synchronizedList(mutableListOf<Boolean>())
    private val cancelled = Collections.synchronizedList(mutableListOf<Boolean>())

    private suspend fun hang(fresh: Boolean): String {
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled += fresh
            throw e
        }
    }

    @Test
    fun `an attempt that answers at once is used, and no second one is started`() = runBlocking {
        val got = hedged("/player", 50, 500, mayHedge = { true }) { fresh -> started += fresh; "first" }
        assertEquals("first", got)
        assertEquals(listOf(false), started.toList())
    }

    @Test
    fun `an attempt that hangs gets a fresh second one at the hedge time, and its answer wins`() = runBlocking {
        var hedges = 0
        val t0 = System.nanoTime()
        val got = hedged("/player", 50, 2_000, mayHedge = { true }, onHedge = { hedges++ }) { fresh ->
            started += fresh
            if (fresh) "fresh" else hang(fresh)
        }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("fresh", got)
        assertEquals(listOf(false, true), started.toList())
        assertEquals(1, hedges)
        assertTrue("answered at the hedge time, not the cap: $tookMs ms", tookMs < 1_000)
        delay(50)
        assertEquals("the hung one was cancelled", listOf(false), cancelled.toList())
    }

    @Test
    fun `both hanging give up at the cap, and both are cancelled`() = runBlocking {
        val t0 = System.nanoTime()
        try {
            hedged("/player", 30, 150, mayHedge = { true }) { fresh -> started += fresh; hang(fresh) }
            fail("it answered")
        } catch (e: StepTimedOut) {
            assertEquals("/player", e.step)
            assertEquals(150L, e.ms)
            assertTrue("a socket timeout to everything else", e is java.net.SocketTimeoutException)
        }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("at the cap: $tookMs ms", tookMs in 140..1_000)
        delay(50)
        assertEquals(setOf(false, true), cancelled.toSet())
    }

    @Test
    fun `an answer from YouTube, a refusal included, returns at once and starts no second try`() = runBlocking {
        val got = hedged("/player", 50, 500, mayHedge = { true }) { fresh ->
            started += fresh
            Result.failure<String>(IllegalStateException("HTTP 400"))
        }
        assertTrue(got.isFailure)
        assertEquals(listOf(false), started.toList())
    }

    @Test
    fun `with no hedge allowed a second try is never started`() = runBlocking {
        try {
            hedged("HEAD", 20, 120, mayHedge = { false }) { fresh -> started += fresh; hang(fresh) }
            fail("it answered")
        } catch (e: StepTimedOut) {
            assertEquals(listOf(false), started.toList())
        }
    }

    @Test
    fun `a connection that fails while the fresh try is out waits for the fresh try`() = runBlocking {
        val got = hedged("/player", 30, 2_000, mayHedge = { true }, settles = { it.isSuccess }) { fresh ->
            if (fresh) {
                delay(100)
                Result.success("fresh")
            } else {
                delay(60)
                Result.failure(IOException("reset"))
            }
        }
        assertEquals("fresh", got.getOrNull())
    }

    @Test
    fun `a resolve past its time is given up, its ad waits added to it`() = runBlocking {
        try {
            withinResolveTime(100) { awaitCancellation() }
            fail("it answered")
        } catch (e: StepTimedOut) {
            assertEquals("resolve", e.step)
        }
        // 150 ms of ads on a 100 ms limit, and 100 ms of work: within it.
        val got = withinResolveTime(100) {
            allowFor(150)
            delay(180)
            "played"
        }
        assertEquals("played", got)
    }
}
