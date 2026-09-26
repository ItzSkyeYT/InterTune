/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.innertube

import com.zionhuang.innertube.utils.Walked
import com.zionhuang.innertube.utils.walkContinuations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync removes whatever a remote list does not contain, so a list that stopped early must never
 * pass for a whole one. The library walk used to stop at a failed continuation and call the part
 * it had a success.
 */
class WalkTest {

    private fun pages(vararg pages: Pair<String, Pair<List<Int>, String?>>): suspend (String) -> Result<Pair<List<Int>, String?>> {
        val map = pages.toMap()
        return { c -> map[c]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("429")) }
    }

    @Test
    fun `a list with no continuation is complete`() = runBlocking {
        assertEquals(Walked(listOf(1, 2), complete = true), walkContinuations(listOf(1, 2), null, pages()))
    }

    @Test
    fun `every page read is complete`() = runBlocking {
        val walked = walkContinuations(
            listOf(1, 2), "c1",
            pages("c1" to (listOf(3, 4) to "c2"), "c2" to (listOf(5) to null)),
        )
        assertEquals(Walked(listOf(1, 2, 3, 4, 5), complete = true), walked)
    }

    @Test
    fun `a continuation that fails half-way is incomplete and keeps what it read`() = runBlocking {
        val walked = walkContinuations(
            listOf(1, 2), "c1",
            pages("c1" to (listOf(3, 4) to "c2")), // c2 answers 429
        )
        assertEquals(listOf(1, 2, 3, 4), walked.items)
        assertFalse(walked.complete)
    }

    @Test
    fun `a continuation in a shape nobody parsed is incomplete`() = runBlocking {
        // An unknown shape comes back as no items and no continuation, which used to end the
        // walk as if the list were over.
        val walked = walkContinuations(
            listOf(1, 2), "c1",
            pages("c1" to (emptyList<Int>() to null)),
        )
        assertEquals(listOf(1, 2), walked.items)
        assertFalse(walked.complete)
    }

    @Test
    fun `a continuation that loops back is incomplete rather than endless`() = runBlocking {
        val walked = walkContinuations(
            listOf(1), "c1",
            pages("c1" to (listOf(2) to "c2"), "c2" to (listOf(3) to "c1")),
        )
        assertEquals(listOf(1, 2, 3), walked.items)
        assertFalse(walked.complete)
    }

    @Test
    fun `an empty first page with nothing after it is complete but empty`() = runBlocking {
        // Callers still refuse to remove anything on an empty list; the walk only says it ended.
        val walked = walkContinuations(emptyList(), null, pages())
        assertTrue(walked.complete)
        assertTrue(walked.items.isEmpty())
    }
}
