/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The playing song's cover is asked for from two places at every song change and again at every
 * play, pause and like. One download and one decode has to serve all of them, a cover nobody wants
 * any more must stop downloading, and one that could not be had must be tried again.
 */
class LastAskedTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val started = Collections.synchronizedList(mutableListOf<String>())
    private val gates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<String?>>())
    private val gaveUp = Collections.synchronizedList(mutableListOf<String>())

    /** Finds nothing until the test opens the gate for that key, like a download that is still running. */
    private val covers = LastAsked<String, String>(
        scope,
        find = { key ->
            started += key
            try {
                gates.getOrPut(key) { CompletableDeferred() }.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                gaveUp += key
                throw e
            }
        },
        instead = { "placeholder for $it" },
    )

    @After
    fun stop() = scope.cancel()

    private fun open(key: String, with: String?) = gates.getOrPut(key) { CompletableDeferred() }.complete(with)

    private fun <T> com.google.common.util.concurrent.ListenableFuture<T>.soon(): T = get(5, TimeUnit.SECONDS)

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) {
            assertTrue("waited five seconds for: $what", System.nanoTime() < until)
            Thread.sleep(2)
        }
    }

    @Test
    fun `two askers at once share one load`() {
        val first = covers.ask("a")
        val second = covers.ask("a")
        assertSame(first, second)
        assertFalse(first.isDone)
        open("a", "cover a")
        assertEquals("cover a", first.soon())
        assertEquals(listOf("a"), started.toList())
    }

    @Test
    fun `asking again later is answered at once, without loading again`() {
        open("a", "cover a")
        assertEquals("cover a", covers.ask("a").soon())
        val again = covers.ask("a")
        assertTrue("a refresh must find the cover ready, or the system is first told there is none", again.isDone)
        assertEquals("cover a", again.soon())
        assertEquals(listOf("a"), started.toList())
    }

    @Test
    fun `a cover that could not be had gets the placeholder and is tried again next time`() {
        open("a", null)
        assertEquals("placeholder for a", covers.ask("a").soon())
        gates["a"] = CompletableDeferred("cover a")           // the connection is back
        waitFor("the failure to be forgotten") { covers.ask("a").soon() == "cover a" }
        assertEquals(listOf("a", "a"), started.toList())
    }

    @Test
    fun `asking for another song gives up on the one still loading`() {
        val skipped = covers.ask("a")
        waitFor("a to start") { started.contains("a") }
        val wanted = covers.ask("b")
        assertNotSame(skipped, wanted)
        waitFor("whoever asked for a to be told it is off") { skipped.isCancelled }
        assertEquals("the download itself has to stop, not only its answer", listOf("a"), gaveUp.toList())
        open("b", "cover b")
        assertEquals("cover b", wanted.soon())
    }

    @Test
    fun `a load given up on before it had begun still tells whoever asked`() {
        val oneThread = Executors.newSingleThreadExecutor()
        val busy = CountDownLatch(1)
        try {
            oneThread.execute { busy.await() }                 // nothing can start until this lets go
            val queued = LastAsked<String, String>(CoroutineScope(oneThread.asCoroutineDispatcher()), find = { started += it; "cover $it" }, instead = { "placeholder" })
            val skipped = queued.ask("a")
            val wanted = queued.ask("b")
            busy.countDown()
            assertEquals("cover b", wanted.soon())
            waitFor("whoever asked for a to be told it is off") { skipped.isCancelled }
            assertEquals(listOf("b"), started.toList())
        } finally {
            busy.countDown()
            oneThread.shutdownNow()
        }
    }

    @Test
    fun `going back to a song given up on loads it again`() {
        covers.ask("a")
        waitFor("a to start") { started.contains("a") }
        open("b", "cover b")
        assertEquals("cover b", covers.ask("b").soon())
        waitFor("a to be given up") { gaveUp.contains("a") }
        gates["a"] = CompletableDeferred("cover a")
        assertEquals("cover a", covers.ask("a").soon())
        assertEquals(listOf("a", "b", "a"), started.toList())
    }

    @Test
    fun `only the last one is kept`() {
        open("a", "cover a")
        open("b", "cover b")
        assertEquals("cover a", covers.ask("a").soon())
        assertEquals("cover b", covers.ask("b").soon())
        assertEquals("cover a", covers.ask("a").soon())
        assertEquals(listOf("a", "b", "a"), started.toList())
    }

    @Test
    fun `an answer somebody cancelled is not handed to the next asker`() {
        val first = covers.ask("a")
        first.cancel(false)
        val second = covers.ask("a")
        assertNotSame(first, second)
        open("a", "cover a")
        assertEquals("cover a", second.soon())
    }

    @Test
    fun `a load that blows up fails its askers and is tried again`() {
        val failing = LastAsked<String, String>(scope, find = { error("no decoder") }, instead = { "placeholder" })
        val first = failing.ask("a")
        try {
            first.soon()
            throw AssertionError("should have failed")
        } catch (e: ExecutionException) {
            assertEquals("no decoder", e.cause?.message)
        } catch (e: CancellationException) {
            throw AssertionError("failed, not cancelled", e)
        }
        waitFor("the failure to be forgotten") { failing.ask("a") !== first }
    }
}
