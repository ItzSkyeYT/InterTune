/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loadRemainingSongs used to retry a failed continuation forever: getContinuation only reassigned
 * the shared `continuation` var on success, so a page that failed (offline, or the coroutine
 * cancelled by leaving the screen, since YouTube.playlistContinuation's runCatching also swallows
 * CancellationException) left the same token in place and the while loop retried it at once,
 * printing a stack trace every time for as long as the process lived.
 */
class OnlinePlaylistContinuationsTest {

    @Test
    fun `a fetch that always fails stops the walk instead of retrying forever`() = runBlocking {
        var calls = 0
        val pages = mutableListOf<List<Int>>()
        val result = followContinuations<Int>(
            start = "c1",
            fetch = { calls++; Result.failure(IllegalStateException("offline")) },
            onPage = { pages += it },
        )
        assertEquals("c1", result)
        assertEquals(1, calls)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun `a cancelled fetch rethrows instead of being swallowed as a failed page`() = runBlocking {
        var calls = 0
        val job = launch {
            followContinuations<Int>(
                start = "c1",
                fetch = { calls++; Result.failure(CancellationException("left the screen")) },
                onPage = { },
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `every page succeeding reaches the end`() = runBlocking {
        val pages = mapOf(
            "c1" to (listOf(1, 2) to "c2"),
            "c2" to (listOf(3) to null),
        )
        val seen = mutableListOf<Int>()
        val result = followContinuations(
            start = "c1",
            fetch = { token -> Result.success(pages.getValue(token)) },
            onPage = { seen += it },
        )
        assertNull(result)
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun `a token already followed is never fetched or appended again`() = runBlocking {
        var c1Calls = 0
        val seen = mutableListOf<Int>()
        val result = followContinuations<Int>(
            start = "c1",
            fetch = { token ->
                when (token) {
                    "c1" -> {
                        c1Calls++
                        Result.success(listOf(1) to "c2")
                    }
                    // Loops back to a token already followed. This is a defensive guard against
                    // that shape of page in general, whatever produces it.
                    "c2" -> Result.success(listOf(2) to "c1")
                    else -> error("unexpected token $token")
                }
            },
            onPage = { seen += it },
        )
        assertNull(result)
        assertEquals(1, c1Calls)
        assertEquals(listOf(1, 2), seen)
    }
}
