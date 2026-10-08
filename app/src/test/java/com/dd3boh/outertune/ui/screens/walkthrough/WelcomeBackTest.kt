/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.hasChips
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The welcome back page: what somebody is told is new depends on where they came from, and every
 * "Show me" has to lead somewhere. A page that lists a thing this build does not have, or a button
 * that points at nothing, is worse than no page.
 */
class WelcomeBackTest {

    private val v0109 = 80          // somebody still on 0.10.9
    private val v011 = 91           // 0.11 as it shipped
    private val v0115 = 92          // the first build of 0.11.5

    private fun ids(things: List<NewThing>) = things.map { it.id }

    @After
    fun clearTargets() {
        listOf(Tour.QUICK_PICKS_CHIPS, Tour.HISTORY, Tour.SETTINGS_YOU).forEach(TourTargets::forget)
    }

    // What is listed

    @Test
    fun `a first install is not welcomed back`() {
        assertEquals(emptyList<String>(), ids(newThingsFor(0, buildVersionCode = v0115)))
        assertEquals(emptyList<String>(), ids(newThingsFor(-1, buildVersionCode = v0115)))
    }

    @Test
    fun `somebody coming from 0_10_9 to 0_11 is shown what 0_11 brought, and nothing of what is still to come`() {
        val things = newThingsFor(v0109, buildVersionCode = v011)
        assertEquals(listOf("quick_picks", "recognise", "widget", "history", "spatial_audio"), ids(things))
        assertTrue(things.all { it.release == "0.11" })
    }

    @Test
    fun `somebody already on 0_11 is shown only what 0_11_5 added`() {
        val things = newThingsFor(v011, buildVersionCode = v0115)
        assertEquals(listOf("living_blur", "share_links"), ids(things))
    }

    @Test
    fun `somebody who skipped a release is shown both, the newest first`() {
        val things = newThingsFor(v0109, buildVersionCode = v0115)
        assertEquals(NEW_THINGS.size, things.size)
        assertEquals(listOf("0.11.5", "0.11"), things.map { it.release }.distinct())
        // each release in one block: the page puts one heading over it
        val releases = things.map { it.release }
        assertEquals(releases, releases.sortedByDescending { it.split('.').map(String::toInt).let { v -> v[0] * 10000 + v[1] * 100 + v.getOrElse(2) { 0 } } })
    }

    @Test
    fun `somebody up to date is shown nothing`() {
        assertEquals(emptyList<String>(), ids(newThingsFor(v0115, buildVersionCode = v0115)))
        assertEquals(emptyList<String>(), ids(newThingsFor(v011, buildVersionCode = v011)))
    }

    @Test
    fun `asked for from Settings, it lists everything whoever asks`() {
        assertEquals(ids(NEW_THINGS), ids(newThingsFor(0, buildVersionCode = v011, everything = true)))
        assertEquals(ids(NEW_THINGS), ids(newThingsFor(v0115, buildVersionCode = v0115, everything = true)))
    }

    // What is not there on this install

    @Test
    fun `Quick picks is not listed where it has no chips to be shown`() {
        val noChips = Install(quickPicksChips = false)

        val things = newThingsFor(v0109, buildVersionCode = v011, install = noChips)
        assertEquals(listOf("recognise", "widget", "history", "spatial_audio"), ids(things))

        // Nor in the list asked for from Settings, where "Show me" would lead nowhere just the
        // same, and it is the only thing that goes.
        val everything = newThingsFor(0, buildVersionCode = v0115, everything = true, install = noChips)
        assertEquals(ids(NEW_THINGS) - "quick_picks", ids(everything))
    }

    @Test
    fun `where Quick picks has its chips it is listed as before`() {
        val things = newThingsFor(v0109, buildVersionCode = v011, install = Install(quickPicksChips = true))
        assertEquals("quick_picks", ids(things).first())
    }

    @Test
    fun `only the engine's two sources have chips`() {
        assertEquals(
            setOf(QuickPicksSource.ENGINE, QuickPicksSource.COMPARE),
            QuickPicksSource.entries.filter { it.hasChips }.toSet()
        )
    }

    @Test
    fun `Home draws the chips by the rule the page lists Quick picks by`() {
        // The page asks hasChips whether there is anything to point at, so Home must not decide
        // by a rule of its own: every place that marks the chips for the tour sits under it.
        val home = File("src/main/java/com/dd3boh/outertune/ui/screens/HomeScreen.kt").readLines()
        val marked = home.indices.filter { "tourTarget(Tour.QUICK_PICKS_CHIPS)" in home[it] }
        assertTrue("Home no longer marks the chips for the tour", marked.isNotEmpty())
        for (line in marked) {
            val guard = (line downTo maxOf(0, line - 8)).map { home[it] }.firstOrNull { it.trimStart().startsWith("if (") }
            assertTrue("line ${line + 1}: the chips are drawn under ${guard?.trim()}", guard != null && "quickPicksSource.hasChips" in guard)
        }
    }

    // Where "Show me" leads

    @Test
    fun `every thing leads somewhere`() {
        for (thing in NEW_THINGS) {
            when (thing.action) {
                NewThingAction.SHOW -> assertTrue("${thing.id} has nowhere to go", thing.stops.isNotEmpty())
                NewThingAction.ADD_WIDGET -> assertTrue("${thing.id} is added, not shown", thing.stops.isEmpty())
            }
            assertTrue("${thing.id}: every stop points at something", thing.stops.all { it.targetId != null })
        }
    }

    @Test
    fun `a setting is reached the way somebody would go there, through the row it is under`() {
        val insideSettings = NEW_THINGS.filter { thing -> thing.stops.any { it.route != null } }
        assertEquals(listOf("living_blur", "share_links", "spatial_audio"), ids(insideSettings))
        for (thing in insideSettings) {
            assertEquals("${thing.id}: the row, then the setting", 2, thing.stops.size)
            assertEquals("${thing.id} starts on the list of settings", Tour.ROUTE_SETTINGS, thing.stops[0].route)
            assertTrue("${thing.id} then goes one screen in", thing.stops[1].route!!.startsWith(Tour.ROUTE_SETTINGS + "/"))
        }
        assertEquals(Tour.ROW_LOOK_AND_FEEL, NEW_THINGS.first { it.id == "living_blur" }.stops[0].targetId)
        assertEquals(Tour.ROUTE_LOOK_AND_FEEL, NEW_THINGS.first { it.id == "living_blur" }.stops[1].route)
        assertEquals(Tour.ROW_PLAYER, NEW_THINGS.first { it.id == "spatial_audio" }.stops[0].targetId)
        assertEquals(Tour.ROUTE_PLAYER, NEW_THINGS.first { it.id == "spatial_audio" }.stops[1].route)
    }

    @Test
    fun `the walk round Settings is its four groups, in the order they are on screen`() {
        assertEquals(
            listOf(Tour.SETTINGS_YOU, Tour.SETTINGS_LOOK_AND_SOUND, Tour.SETTINGS_KEPT, Tour.SETTINGS_REST),
            SETTINGS_TOUR.map { it.targetId }
        )
        assertTrue(SETTINGS_TOUR.all { it.route == Tour.ROUTE_SETTINGS })
    }

    @Test
    fun `a first install is walked round the app and then round Settings`() {
        val tour = tourFor(seenVersionCode = 0, buildVersionCode = v011, settingsWalk = true)
        assertEquals(TOUR_STOPS.map { it.id } + SETTINGS_TOUR.map { it.id }, tour.map { it.id })
        // The walk follows the stop that points at the way into Settings.
        assertEquals(Tour.SETTINGS, tour[tour.size - SETTINGS_TOUR.size - 1].targetId)
    }

    @Test
    fun `somebody who has had the tour is not walked round Settings uninvited`() {
        assertTrue(tourFor(seenVersionCode = v0109, buildVersionCode = v011, settingsWalk = true).none { it in SETTINGS_TOUR })
        assertEquals(emptyList<TourStop>(), tourFor(seenVersionCode = v011, buildVersionCode = v011, settingsWalk = true))
    }

    @Test
    fun `without the walk a first install gets the tour of the app alone`() {
        assertEquals(TOUR_STOPS.map { it.id }, tourFor(seenVersionCode = 0, buildVersionCode = v011, settingsWalk = false).map { it.id })
    }

    @Test
    fun `no two stops share an id, or a rotation would bring back the wrong one`() {
        val every = TOUR_STOPS + SETTINGS_TOUR + SETTINGS_CLOSER_LOOK + NEW_THINGS.flatMap { it.stops }
        val twice = every.groupBy { it.id }.filter { (_, same) -> same.distinct().size > 1 }.keys
        assertEquals(emptySet<String>(), twice)
        assertEquals(every.map { it.id }.distinct().size, ALL_TOUR_STOPS.size)
        assertEquals(NEW_THINGS.size, NEW_THINGS.map { it.id }.distinct().size)
        assertFalse("the walk is ticked off under an id of its own", SETTINGS_WALK in NEW_THINGS.map { it.id })
    }

    // Starting a tour that leaves Home

    private fun onScreen(id: String) {
        TourTargets.arrive(id, BringIntoViewRequester())
        TourTargets.put(id, Rect(0f, 0f, 10f, 10f), IntSize(10, 10))
    }

    @Test
    fun `a stop on another screen is kept, though what it points at is not there yet`() {
        val state = TourState()
        state.start(NEW_THINGS.first { it.id == "living_blur" }.stops)
        assertTrue(state.running)
        assertEquals(2, state.stops.size)
    }

    @Test
    fun `a stop on Home with nothing to point at is dropped, and a tour of nothing does not start`() {
        val chips = NEW_THINGS.first { it.id == "quick_picks" }.stops
        val state = TourState()
        state.start(chips)
        assertFalse("Quick picks has no chips on this install", state.running)
        onScreen(Tour.QUICK_PICKS_CHIPS)
        state.start(chips)
        assertTrue(state.running)
    }

    // Back, in a tour that has left the screen it started on

    @Test
    fun `Back from a setting to the row it is under says which screen to go back to`() {
        val state = TourState()
        state.start(NEW_THINGS.first { it.id == "living_blur" }.stops)
        state.next()
        assertEquals(Tour.ROUTE_LOOK_AND_FEEL, state.current!!.route)

        val backTo = state.back()

        assertEquals("the tour is on the row again", "living_blur_row", state.current!!.id)
        assertEquals("and the screen has to follow it", Tour.ROUTE_SETTINGS, backTo?.route)
    }

    @Test
    fun `Back between two stops on one screen leaves the screen alone`() {
        val state = TourState()
        state.start(SETTINGS_TOUR)
        state.next()
        state.next()

        assertNull(state.back())
        assertEquals("settings_look_and_sound", state.current!!.id)
    }

    @Test
    fun `Back from another screen to a stop on Home goes Home`() {
        // No tour does this today, but a stop with no route means Home, and going back to one
        // must say so and not read as nothing to do.
        onScreen(Tour.HISTORY)
        val home = NEW_THINGS.first { it.id == "history" }.stops.single()
        val inSettings = SETTINGS_TOUR.first()
        val state = TourState()
        state.start(listOf(home, inSettings))
        state.next()

        val backTo = state.back()

        assertEquals(home, backTo)
        assertNull("Home is the stop with no route", backTo!!.route)
    }

    @Test
    fun `Back on the first stop is no step and no move`() {
        val state = TourState()
        state.start(SETTINGS_TOUR)

        assertNull(state.back())
        assertEquals(0, state.index)
        assertTrue(state.running)
    }

    @Test
    fun `scrolled out of view it is still there to be scrolled to, and has no place to cut a hole at`() {
        TourTargets.arrive(Tour.SETTINGS_YOU, BringIntoViewRequester())
        TourTargets.put(Tour.SETTINGS_YOU, Rect.Zero, IntSize(900, 400))
        assertTrue(TourTargets.known(Tour.SETTINGS_YOU))
        assertNull(TourTargets[Tour.SETTINGS_YOU])
        TourTargets.put(Tour.SETTINGS_YOU, Rect(0f, 300f, 900f, 700f), IntSize(900, 400))
        assertEquals(Rect(0f, 300f, 900f, 700f), TourTargets[Tour.SETTINGS_YOU])
        TourTargets.forget(Tour.SETTINGS_YOU)
        assertFalse(TourTargets.known(Tour.SETTINGS_YOU))
    }
}
