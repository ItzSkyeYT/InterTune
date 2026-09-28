/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingCancellableTest {
    @Test
    fun `a normal return is a success`() {
        val result = runCatchingCancellable { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `an ordinary exception becomes a failed Result, like plain runCatching`() {
        val boom = IllegalStateException("boom")
        val result = runCatchingCancellable<Int> { throw boom }
        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test(expected = CancellationException::class)
    fun `a CancellationException is rethrown, never wrapped in a Result`() {
        runCatchingCancellable<Int> { throw CancellationException("cancelled") }
    }

    @Test
    fun `a CancellationException subclass is rethrown too`() {
        // kotlinx.coroutines throws subclasses of CancellationException (e.g. JobCancellationException)
        // far more often than the base class itself; plain runCatching's catch (e: Throwable) would
        // swallow those the same way, so the fix has to catch by the base type, not by exact class.
        class JobCancellationExceptionLike(message: String) : CancellationException(message)

        var rethrown = false
        try {
            runCatchingCancellable<Int> { throw JobCancellationExceptionLike("job cancelled") }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    @Test
    fun `a cancelled coroutine stops at the call instead of carrying on`() = runBlocking {
        var carriedOn = false
        val job = launch {
            runCatchingCancellable { delay(60_000) }
            carriedOn = true
        }
        yield()
        job.cancelAndJoin()
        assertFalse(carriedOn)
    }
}
