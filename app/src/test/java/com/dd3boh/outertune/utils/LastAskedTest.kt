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
import java.util.concurrent.atomic.AtomicLong

/**
 * The playing song's cover is asked for from two places at every song change and again at every
 * play, pause and like. One download and one decode has to serve all of them, a cover nobody wants
 * any more must stop downloading, and one that could not be had must be tried again.
 *
 * And nobody waits for a large cover while a small one is on the phone: the small one answers at
 * once, and the large one is handed to whoever asks after it has arrived.
 */
class LastAskedTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val started = Collections.synchronizedList(mutableListOf<String>())
    private val gates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<String?>>())
    private val gaveUp = Collections.synchronizedList(mutableListOf<String>())

    /** What is on the phone already for a key: handed over before the gate is waited at. */
    private val atHand = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val toldBetter = Collections.synchronizedList(mutableListOf<String>())

    /** Finds nothing until the test opens the gate for that key, like a download that is still running. */
    private val covers = LastAsked<String, String>(
        scope,
        find = { key, meanwhile ->
            started += key
            atHand[key]?.let(meanwhile)
            try {
                gate(key).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                gaveUp += key
                throw e
            }
        },
        instead = { "placeholder for $it" },
        better = { toldBetter += it },
    )

    /**
     * One gate per key, whoever comes for it first. Under a lock: a cover at hand answers the test
     * before the load has reached its gate, and the two must not each make their own.
     */
    private fun gate(key: String): CompletableDeferred<String?> = synchronized(gates) { gates.getOrPut(key) { CompletableDeferred() } }

    @After
    fun stop() = scope.cancel()

    private fun open(key: String, with: String?) = gate(key).complete(with)

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
            val queued = LastAsked<String, String>(CoroutineScope(oneThread.asCoroutineDispatcher()), find = { key, _ -> started += key; "cover $key" }, instead = { "placeholder" })
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
        val failing = LastAsked<String, String>(scope, find = { _, _ -> error("no decoder") }, instead = { "placeholder" })
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

    @Test
    fun `a cover that is slow to come is answered at once with the one at hand`() {
        atHand["a"] = "small a"
        assertEquals("the gate is shut: the large one is still on its way", "small a", covers.ask("a").soon())
        val again = covers.ask("a")
        assertTrue("a refresh while the large one is on its way must not be told there is no cover", again.isDone)
        assertEquals("small a", again.soon())
        assertEquals("and it starts nothing", listOf("a"), started.toList())
        assertTrue(toldBetter.isEmpty())
    }

    @Test
    fun `the real cover takes the place of the one at hand for whoever asks after it has arrived`() {
        atHand["a"] = "small a"
        assertEquals("small a", covers.ask("a").soon())
        open("a", "cover a")
        waitFor("word that the real one is there") { toldBetter.toList() == listOf("a") }
        val again = covers.ask("a")
        assertTrue("it is in hand: nobody may be told there is no cover", again.isDone)
        assertEquals("cover a", again.soon())
        assertSame(again, covers.ask("a"))
        assertEquals(listOf("a"), started.toList())
    }

    @Test
    fun `whoever is told the real cover is there finds it there`() {
        // the session asks again from inside the telling, before anything else can happen
        val found = Collections.synchronizedList(mutableListOf<String>())
        lateinit var telling: LastAsked<String, String>
        telling = LastAsked(
            scope,
            find = { key, meanwhile -> meanwhile("small $key"); gate(key).await() },
            instead = { "placeholder" },
            better = { key -> telling.ask(key).let { found += if (it.isDone) it.soon() else "nothing yet" } },
        )
        assertEquals("small a", telling.ask("a").soon())
        open("a", "cover a")
        waitFor("the telling") { found.isNotEmpty() }
        assertEquals(listOf("cover a"), found.toList())
    }

    @Test
    fun `a real cover that could not be had leaves the one at hand, and is tried again at each ask`() {
        atHand["a"] = "small a"
        assertEquals("small a", covers.ask("a").soon())
        open("a", null)                                        // no connection
        waitFor("a second try") {
            val again = covers.ask("a")
            assertTrue("never a wait, and never the placeholder, while a cover is at hand", again.isDone)
            assertEquals("small a", again.soon())
            started.size >= 2
        }
        assertTrue(toldBetter.isEmpty())
        gates["a"] = CompletableDeferred("cover a")           // the connection is back
        waitFor("word that the real one is there") { covers.ask("a"); toldBetter.toList() == listOf("a") }
        assertEquals("cover a", covers.ask("a").soon())
    }

    @Test
    fun `a real cover that could not be had is left alone for a while before it is tried again`() {
        // a poor connection has the notification built again and again, and each time it asks
        val clock = AtomicLong(0)
        val patient = LastAsked<String, String>(
            scope,
            find = { key, meanwhile -> started += key; meanwhile("small $key"); null },
            instead = { "placeholder" },
            retryAfterMs = 15_000,
            now = clock::get,
        )
        assertEquals("small a", patient.ask("a").soon())
        repeat(20) {
            assertEquals("small a", patient.ask("a").soon())
            Thread.sleep(5)
        }
        assertEquals("asking again straight away starts nothing", listOf("a"), started.toList())
        clock.set(15_000)
        waitFor("another try") { patient.ask("a"); started.size == 2 }
        repeat(20) {
            assertEquals("small a", patient.ask("a").soon())
            Thread.sleep(5)
        }
        assertEquals("and that one is left alone as long again", 2, started.size)
    }

    @Test
    fun `a try for the real cover that is still running is not started again`() {
        atHand["a"] = "small a"
        repeat(5) { assertEquals("small a", covers.ask("a").soon()) }
        assertEquals(listOf("a"), started.toList())
    }

    @Test
    fun `nothing is told about a cover nobody wants any more`() {
        atHand["a"] = "small a"
        assertEquals("small a", covers.ask("a").soon())
        open("b", "cover b")
        assertEquals("cover b", covers.ask("b").soon())
        waitFor("a to be given up") { gaveUp.contains("a") }
        Thread.sleep(50)
        assertTrue(toldBetter.isEmpty())
        assertEquals("cover b", covers.ask("b").soon())
    }

    @Test
    fun `a cover that comes whole is told to nobody`() {
        open("a", "cover a")
        assertEquals("cover a", covers.ask("a").soon())
        Thread.sleep(50)
        assertTrue("only a cover that replaces a lesser one is news", toldBetter.isEmpty())
    }

    @Test
    fun `a real cover that blows up after the one at hand went out leaves that one`() {
        val failing = LastAsked<String, String>(scope, find = { key, meanwhile -> meanwhile("small $key"); error("no decoder") }, instead = { "placeholder" })
        assertEquals("small a", failing.ask("a").soon())
        Thread.sleep(50)
        val again = failing.ask("a")
        assertTrue(again.isDone)
        assertEquals("small a", again.soon())
    }
}
