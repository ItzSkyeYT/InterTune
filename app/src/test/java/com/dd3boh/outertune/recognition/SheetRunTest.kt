/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the playlist sheet makes of the run it finds: see [SheetRun].
 *
 * The sheet is opened on [here] throughout. A run's playlist is the one the engine's run adds to,
 * or its last run's once stopped, and null for the What's playing? screen's.
 */
class SheetRunTest {

    private val here = "LPhere0001"
    private val there = "LPthere001"

    @Test
    fun `nothing has run yet, so the sheet starts its own`() {
        assertEquals(SheetRun.Idle, SheetRun.of(running = false, runId = null, runName = null, sheetId = here))
    }

    @Test
    fun `a run for this playlist is joined, going or stopped on its answer`() {
        assertEquals(SheetRun.Own, SheetRun.of(running = true, runId = here, runName = "Road trip", sheetId = here))
        // Listen once stops on what it found and waits for a pick, which is this sheet's to show.
        assertEquals(SheetRun.Own, SheetRun.of(running = false, runId = here, runName = "Road trip", sheetId = here))
    }

    @Test
    fun `the screen's run is not joined`() {
        // Joined, nothing it heard went into the playlist and Add put the song in the screen's list.
        assertEquals(SheetRun.Other(null), SheetRun.of(running = true, runId = null, runName = null, sheetId = here))
    }

    @Test
    fun `another playlist's run is not joined, and is named`() {
        assertEquals(
            SheetRun.Other("Road trip"),
            SheetRun.of(running = true, runId = there, runName = "Road trip", sheetId = here),
        )
    }

    @Test
    fun `what a stopped run of somebody else's left on show is not this playlist's`() {
        // The screen's Listen once stopping on its answer while the sheet was open over it.
        assertEquals(SheetRun.Idle, SheetRun.of(running = false, runId = null, runName = null, sheetId = here))
        assertEquals(SheetRun.Idle, SheetRun.of(running = false, runId = there, runName = "Road trip", sheetId = here))
    }

    @Test
    fun `closing the sheet leaves somebody else's run going, and puts down its own or none`() {
        assertFalse(SheetRun.Other(null).resetOnClose)
        assertFalse(SheetRun.Other("Road trip").resetOnClose)
        assertTrue(SheetRun.Own.resetOnClose)
        assertTrue(SheetRun.Idle.resetOnClose)
    }
}
