/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which question [LoudnessRepair.isRunning] has to ask, and why the obvious one is wrong.
 *
 * This pins a property of kotlinx.coroutines rather than of this app, on purpose. The reading it
 * rules out looks correct, was shipped, and produced a bug that only appears if you cancel and
 * immediately restart, which is exactly the thing nobody does while testing by hand.
 */
class JobStateTest {

    /** Runs until released, and holds the coroutine inside its unwind the way a finally block does. */
    private class Holder {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        fun launchIn(scope: CoroutineScope): Job = scope.launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                // Where the repair publishes its stopped-early result. Still running, by any
                // definition that matters.
                withContext(NonCancellable) { release.await() }
            }
        }
    }

    @Test
    fun `a cancelled job is already inactive while its body is still running`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val holder = Holder()
        val job = holder.launchIn(scope)
        holder.started.await()

        assertTrue(job.isActive)

        job.cancel()

        // The trap. isActive is false the instant cancel returns, but the body has not finished
        // and will not for as long as its unwind takes.
        assertFalse("isActive goes false immediately, which is why it cannot be trusted", job.isActive)
        assertFalse("the body is still running", job.isCompleted)

        holder.release.complete(Unit)
        job.join()

        assertTrue(job.isCompleted)
    }

    @Test
    fun `isCompleted is false for both running and cancelling, and true only when done`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val holder = Holder()

        // Nothing has ever run. A null job compares false, which is correctly "not running".
        val never: Job? = null
        assertFalse(never?.isCompleted == false)

        val job = holder.launchIn(scope)
        holder.started.await()
        assertTrue("running", job.isCompleted == false)

        job.cancel()
        assertTrue("cancelling, still running", job.isCompleted == false)

        holder.release.complete(Unit)
        job.join()
        assertFalse("done", job.isCompleted == false)
    }
}
