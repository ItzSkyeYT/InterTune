/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.extensions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The wait teardown makes for the queue save: as long as the save takes when it lands, and no
 * longer than the bound when it does not.
 */
class JoinWithinTest {

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun `a job that has ended is not waited for`() {
        val done = Job().apply { complete() }
        var landed = false
        val took = millis { landed = done.joinWithin(PATIENT_MS) }
        assertTrue(landed)
        assertTrue("took $took ms", took < PATIENT_MS / 2)
    }

    @Test
    fun `a job that ends in time is waited for until it has`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val written = mutableListOf<String>()
        val save = scope.launch {
            Thread.sleep(50)
            synchronized(written) { written += "saved" }
        }
        assertTrue(save.joinWithin(PATIENT_MS))
        assertEquals("the wait ended before the save had", listOf("saved"), synchronized(written) { written.toList() })
        scope.cancel()
    }

    @Test
    fun `a job that does not end is left after the bound, still running`() {
        val stuck = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // A thread parked in a call that cannot be called back, as a write is when the database
        // has stopped answering.
        val save = scope.launch { stuck.await() }
        var landed = true
        val took = millis { landed = save.joinWithin(BOUND_MS) }
        assertFalse(landed)
        assertTrue("gave up after $took ms, before the bound", took >= BOUND_MS)
        assertTrue("gave up after $took ms, long after the bound", took < BOUND_MS + SLACK_MS)
        assertTrue("the save was cancelled by the wait giving up", save.isActive)
        // It still lands when whatever held it lets go.
        stuck.countDown()
        assertTrue(save.joinWithin(PATIENT_MS))
        scope.cancel()
    }

    @Test
    fun `a job that failed counts as ended`() {
        val failed = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("no")) }
        assertTrue(failed.joinWithin(PATIENT_MS))
    }

    private companion object {
        const val BOUND_MS = 150L
        /** For the waits that end by themselves: a bound a busy build machine cannot run into. */
        const val PATIENT_MS = 20_000L
        /** Room for a loaded build machine: the test asserts an order of magnitude, not a deadline. */
        const val SLACK_MS = 2_000L
    }
}
