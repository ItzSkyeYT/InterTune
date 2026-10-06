/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
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

    /** As the control would say of itself once it is drawn. */
    private fun onScreen(id: String) {
        TourTargets.arrive(id, BringIntoViewRequester())
        TourTargets.put(id, Rect(0f, 0f, 10f, 10f), IntSize(10, 10))
    }

    @Test
    fun `a rotation keeps the tour on the stop it had reached`() {
        onScreen(Tour.SEARCH_BAR)
        onScreen(Tour.RECOGNISE)
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
    fun `a tour that has gone into Settings comes back there too`() {
        val state = TourState()
        state.start(SETTINGS_TOUR)
        state.next()
        val restored = roundTrip(state)
        assertTrue(restored.running)
        assertEquals("settings_look_and_sound", restored.current!!.id)
        assertEquals(SETTINGS_TOUR.size, restored.stops.size)
    }

    @Test
    fun `no tour running saves nothing, so a fresh one comes back`() {
        // listSaver saves an empty list as null, and rememberSaveable then runs its own initialiser.
        val saved = with(TourState.Saver) { scope.save(TourState()) }
        assertNull(saved)
    }
}
