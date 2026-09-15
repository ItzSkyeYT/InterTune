/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a candidate is the same kind of thing as the run it would join. */
class TagFitTest {

    private val slowedRun = TagFit.context(List(5) { setOf("slowed") })
    private val plainRun = TagFit.context(List(5) { emptySet<String>() })

    @Test
    fun `a slowed edit fits a slowed run and an untreated song does not`() {
        assertTrue(TagFit.score(setOf("slowed"), slowedRun) > 0.9)
        assertTrue(TagFit.score(emptySet(), slowedRun) < 0.0)
    }

    @Test
    fun `and the reverse, which is the half nothing else notices`() {
        // Dropping a slowed edit into a run of ordinary recordings is the same mistake backwards.
        assertTrue(TagFit.score(emptySet(), plainRun) > 0.0)
        assertEquals(0.0, TagFit.score(setOf("slowed"), plainRun), 1e-9)
    }

    @Test
    fun `with nothing playing yet, nothing is claimed either way`() {
        assertEquals(0.0, TagFit.score(setOf("slowed"), TagFit.context(emptyList())), 1e-9)
        assertEquals(0.0, TagFit.score(emptySet(), TagFit.context(emptyList())), 1e-9)
    }

    @Test
    fun `a run that is turning is followed, not averaged away`() {
        // Four slowed then one plain, newest first: the newest carries the most weight.
        val turning = TagFit.context(listOf(emptySet(), setOf("slowed"), setOf("slowed"), setOf("slowed"), setOf("slowed")))
        val settled = TagFit.context(List(5) { setOf("slowed") })
        assertTrue(TagFit.score(setOf("slowed"), turning) < TagFit.score(setOf("slowed"), settled))
    }

    @Test
    fun `only the last few listens count, so an old run does not hold the session for ever`() {
        val long = TagFit.context(List(5) { setOf("slowed") } + List(20) { setOf("live") })
        assertEquals(0.0, long.tags["live"] ?: 0.0, 1e-9)
    }

    @Test
    fun `an untreated run is not the same as knowing nothing`() {
        // These produce the same empty tag map and must not behave the same way.
        assertTrue(TagFit.context(List(5) { emptySet<String>() }).known)
        assertTrue(!TagFit.context(emptyList()).known)
    }
}
