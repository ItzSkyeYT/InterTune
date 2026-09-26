/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A running tour, and where it had got to, come back after the activity is recreated. */
class TourStateSaverTest {

    private val scope = SaverScope { true }

    @After
    fun clearTargets() {
        TourTargets.forget(Tour.SEARCH_BAR)
        TourTargets.forget(Tour.RECOGNISE)
    }

    private fun roundTrip(state: TourState): TourState {
        val saved = with(TourState.Saver) { scope.save(state) }!!
        return TourState.Saver.restore(saved)!!
    }

    @Test
    fun `a rotation keeps the tour on the stop it had reached`() {
        TourTargets.put(Tour.SEARCH_BAR, Rect(0f, 0f, 10f, 10f))
        TourTargets.put(Tour.RECOGNISE, Rect(0f, 0f, 10f, 10f))
        val state = TourState()
        state.start(TOUR_STOPS)
        state.next()
        state.next()
        val before = state.current!!.id

        val restored = roundTrip(state)
        assertTrue(restored.running)
        assertEquals(state.stops.map { it.id }, restored.stops.map { it.id })
        assertEquals(before, restored.current!!.id)
    }

    @Test
    fun `no tour running saves nothing, so a fresh one comes back`() {
        // listSaver saves an empty list as null, and rememberSaveable then runs its own initialiser.
        val saved = with(TourState.Saver) { scope.save(TourState()) }
        assertNull(saved)
    }
}
