/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A read that does not come back costs its caller the bound and no more, and the callers after it
 * nothing, until the database answers again. A latch stands in for the database that is stuck.
 */
class BoundedReadTest {

    private val stuck = CountDownLatch(1)
    private val stalls = AtomicInteger()
    private val reads = BoundedRead(timeoutMs = BOUND_MS, onStall = { stalls.incrementAndGet() })

    /** For the tests where the read answers: a bound a busy build machine cannot run into by itself. */
    private val patient = BoundedRead(timeoutMs = PATIENT_MS, onStall = { stalls.incrementAndGet() })

    /** Lets go of whatever a test left parked, so no thread outlives its test. */
    @After
    fun release() = stuck.countDown()

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun `a read that answers gives what it read, without waiting for the bound`() {
        var answer: String? = null
        val took = millis { answer = patient.orNull { "row" } }
        assertEquals("row", answer)
        assertTrue("took $took ms", took < PATIENT_MS / 2)
        assertEquals(0, stalls.get())
    }

    @Test
    fun `a row that is not there is null, and the next read is made as usual`() {
        assertNull(patient.orNull<String> { null })
        assertEquals("row", patient.orNull { "row" })
        assertEquals(0, stalls.get())
    }

    @Test
    fun `the read is not made on the thread that asks`() {
        val asking = Thread.currentThread()
        val reading = patient.orNull { Thread.currentThread() }
        assertTrue(reading != null && reading !== asking)
    }

    @Test
    fun `a read that does not come back is given up at the bound`() {
        var answer: String? = "unset"
        val took = millis { answer = reads.orNull { stuck.await(); "late" } }
        assertNull(answer)
        assertTrue("gave up after $took ms, before the bound", took >= BOUND_MS)
        assertTrue("gave up after $took ms, long after the bound", took < BOUND_MS + SLACK_MS)
        assertEquals(1, stalls.get())
    }

    @Test
    fun `while that read is still out the next ones are not made and do not wait`() {
        assertNull(reads.orNull { stuck.await(); "late" })
        val made = AtomicInteger()
        val took = millis {
            repeat(20) { assertNull(reads.orNull { made.incrementAndGet(); "row" }) }
        }
        assertEquals("a read was sent after the one that is stuck", 0, made.get())
        assertTrue("twenty reads behind a stuck one took $took ms", took < BOUND_MS)
        assertEquals("said once, not for every read turned away", 1, stalls.get())
    }

    @Test
    fun `once the late read has come back, reads are made again`() {
        val back = CountDownLatch(1)
        assertNull(reads.orNull { stuck.await(); back.countDown(); "late" })
        assertNull(reads.orNull { "row" })
        stuck.countDown()
        assertTrue(back.await(SLACK_MS, TimeUnit.MILLISECONDS))
        assertEquals("row", untilAnswered())
    }

    @Test
    fun `a second stall is said again`() {
        val first = CountDownLatch(1)
        assertNull(reads.orNull { first.await(); "late" })
        assertEquals(1, stalls.get())
        first.countDown()
        assertEquals("row", untilAnswered())
        val before = stalls.get()
        assertNull(reads.orNull { stuck.await(); "late" })
        assertEquals(before + 1, stalls.get())
    }

    /**
     * Asks until a read is answered. The late read has returned by now, but its future is marked
     * done a moment after, and on a busy machine a read can run into the short bound by itself.
     */
    private fun untilAnswered(): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SLACK_MS)
        while (System.nanoTime() < deadline) {
            reads.orNull { "row" }?.let { return it }
            Thread.sleep(5)
        }
        return null
    }

    @Test
    fun `a read that fails throws what the read threw`() {
        val thrown = IllegalStateException("no such table")
        try {
            patient.orNull<String> { throw thrown }
            fail("the failure was swallowed")
        } catch (e: IllegalStateException) {
            assertSame(thrown, e)
        }
        assertEquals("a failure is not a stall", 0, stalls.get())
        assertEquals("row", patient.orNull { "row" })
    }

    /**
     * The player cancels a load by interrupting its thread, on every skip and seek, so this is the
     * common way a wait ends early. It has to end at once, and it says nothing about the database.
     */
    @Test
    fun `a caller interrupted while it waits is told so, and the database is not taken for stuck`() {
        val waiting = CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Any?>("unset")
        val loader = Thread {
            outcome.set(
                try {
                    patient.orNull { waiting.countDown(); stuck.await(); "late" }
                } catch (e: InterruptedException) {
                    e
                }
            )
        }
        loader.start()
        assertTrue(waiting.await(SLACK_MS, TimeUnit.MILLISECONDS))
        val took = millis {
            loader.interrupt()
            loader.join(PATIENT_MS / 2)
        }
        assertTrue("the wait went on for $took ms after the interrupt", took < SLACK_MS)
        assertTrue("got ${outcome.get()}", outcome.get() is InterruptedException)
        assertEquals("an interrupt is not a stall", 0, stalls.get())
        // The read it left behind is still out, and the next one is made all the same.
        assertEquals("row", patient.orNull { "row" })
    }

    @Test
    fun `readers that ask at the same moment each wait the bound once`() {
        val answers = AtomicInteger()
        val done = CountDownLatch(4)
        val took = millis {
            repeat(4) {
                Thread {
                    if (reads.orNull { stuck.await(); "late" } != null) answers.incrementAndGet()
                    done.countDown()
                }.start()
            }
            assertTrue(done.await(BOUND_MS + SLACK_MS, TimeUnit.MILLISECONDS))
        }
        assertEquals(0, answers.get())
        assertTrue("four readers at once took $took ms", took < BOUND_MS + SLACK_MS)
    }

    private companion object {
        const val BOUND_MS = 150L
        const val PATIENT_MS = 20_000L
        /** Room for a loaded build machine: the test asserts an order of magnitude, not a deadline. */
        const val SLACK_MS = 2_000L
    }
}
