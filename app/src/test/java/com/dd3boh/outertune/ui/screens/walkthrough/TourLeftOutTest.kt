/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.saveable.SaverScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop whose setting is not on its screen: hidden while signed out, behind another switch,
 * behind a flag. A stop on another screen is taken on trust when the tour starts, and this is
 * what becomes of it when the tour gets there and finds nothing to point at. It used to be shown
 * all the same, as a bubble in the middle of the screen about a control that was not there.
 */
class TourLeftOutTest {

    private val scope = SaverScope { true }

    // Four stops over three screens: the list of settings, Player and audio, the list, Look and feel.
    private val four = NEW_THINGS.first { it.id == "spatial_audio" }.stops + NEW_THINGS.first { it.id == "living_blur" }.stops
    private val routes = four.mapNotNull { it.route }.distinct()

    private fun touring(): TourState = TourState().apply { start(four) }

    private fun mark(stop: TourStop) = TourTargets.arrive(stop.targetId!!, BringIntoViewRequester())

    // The first-run tutorial's stops, all on Home.
    private val home = TOUR_STOPS.filter { it.targetId != null }

    @After
    fun clear() {
        (four + home).forEach { TourTargets.forget(it.targetId!!) }
        (routes + Tour.ROUTE_HOME).forEach { route -> while (TourTargets.drawn(route)) TourTargets.screenLeft(route) }
    }

    @Test
    fun `a stop on Home whose control has gone since the tour began gets no bubble either`() {
        // The chips over Quick picks are on Home while the row loads. On a new install the row
        // then falls back to YouTube's picks and they go, and a tour that began in that moment
        // reached the stop with its bubble in the middle of the screen, describing them.
        TourTargets.screenArrived(Tour.ROUTE_HOME)
        home.forEach(::mark)
        val state = TourState().apply { start(home) }
        assertEquals(home.size, state.stops.size)

        TourTargets.forget(Tour.QUICK_PICKS_CHIPS)
        state.next()

        assertEquals("Next goes past it", "recognise", state.current?.id)
        assertEquals("and the counter no longer counts it", home.size - 1, state.stops.size)
        state.back()
        assertEquals("search", state.current?.id)
    }

    @Test
    fun `a stop on Home is not judged while Home is not the screen that is up`() {
        home.forEach(::mark)
        val state = TourState().apply { start(home) }
        TourTargets.forget(Tour.QUICK_PICKS_CHIPS)

        state.next()

        assertEquals("quick_picks", state.current?.id)
        assertEquals(home.size, state.stops.size)
    }

    @Test
    fun `a stop whose setting is not on its screen is left out, and the tour is on the next one`() {
        val state = touring()
        state.next()
        val gone = state.current!!

        state.leaveOut()

        assertTrue(state.running)
        assertEquals(four[2], state.current)
        assertFalse(gone in state.stops)
        assertEquals("the counter no longer counts it", four.size - 1, state.stops.size)
    }

    @Test
    fun `stepping back onto a setting that is not there goes on back, not forward again`() {
        val state = touring()
        state.next()
        state.next()
        state.back()

        state.leaveOut()

        assertEquals(four[0], state.current)
        assertEquals(four.size - 1, state.stops.size)
    }

    @Test
    fun `when the last stop is not there the tour is over`() {
        val state = touring()
        repeat(four.size - 1) { state.next() }
        state.leaveOut()
        assertFalse(state.running)
    }

    @Test
    fun `when nothing that was to be shown is there the tour is over`() {
        val state = TourState().apply { start(four.take(2)) }
        state.leaveOut()
        assertTrue("the setting itself is still to come", state.running)
        state.leaveOut()
        assertFalse(state.running)
    }

    @Test
    fun `on a screen that is already up, a setting that is not on it gets no bubble at all`() {
        // The tour is on the list of settings, which is drawn with the first of its rows marked
        // and not the other. The stop between them is on a screen that has yet to be gone to.
        TourTargets.screenArrived(Tour.ROUTE_SETTINGS)
        mark(four[0])
        val onTheList = listOf(four[0], four[2], four[1])
        val state = TourState().apply { start(onTheList) }

        state.next()

        assertEquals("Next goes past it", four[1], state.current)
        assertEquals(2, state.stops.size)
        state.back()
        assertEquals("and Back does not find it again", four[0], state.current)
    }

    @Test
    fun `a stop on a screen the tour has not reached is not judged before it gets there`() {
        // Nothing is drawn: every setting is taken on trust until its screen is.
        val state = touring()
        repeat(four.size - 1) { state.next() }
        assertEquals(four.last(), state.current)
        assertEquals(four.size, state.stops.size)
    }

    @Test
    fun `a screen says when it is drawn, which is when a setting missing from it is known to be missing`() {
        val route = Tour.ROUTE_PLAYER
        assertFalse(TourTargets.drawn(route))
        TourTargets.screenArrived(route)
        assertTrue(TourTargets.drawn(route))
        // Going back and forth, the screen that leaves is still there while the same one arrives.
        TourTargets.screenArrived(route)
        TourTargets.screenLeft(route)
        assertTrue("the one that arrived was taken for the one that left", TourTargets.drawn(route))
        TourTargets.screenLeft(route)
        assertFalse(TourTargets.drawn(route))
    }

    @Test
    fun `a row that leaves does not take the mark of the same row arriving`() {
        // Back and Next in quick succession: the screen going out is disposed after the same
        // screen coming in has reported, and it used to forget the mark for both.
        val old = BringIntoViewRequester()
        val new = BringIntoViewRequester()
        val id = four[1].targetId!!
        TourTargets.arrive(id, old)
        val first = TourTargets.holder(id)
        TourTargets.arrive(id, new)
        assertTrue("the row coming in is another one to scroll to", TourTargets.holder(id) !== first)
        TourTargets.leave(id, old)
        assertTrue(TourTargets.known(id))
        TourTargets.leave(id, new)
        assertFalse(TourTargets.known(id))
    }

    @Test
    fun `a rotation does not bring back a stop that was left out`() {
        val state = touring()
        state.next()
        val gone = state.current!!
        state.leaveOut()

        val saved = with(TourState.Saver) { scope.save(state) }!!
        val restored = TourState.Saver.restore(saved)!!

        assertFalse(gone in restored.stops)
        assertEquals(state.current, restored.current)
        assertEquals(four.size - 1, restored.stops.size)
    }
}
