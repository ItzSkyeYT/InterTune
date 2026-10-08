/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.runtime.saveable.SaverScope
import com.dd3boh.outertune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The question the tutorial ends on: whether to go on into the settings themselves. Where it is
 * asked, what its two answers do, and that nobody is asked who skipped the tutorial or who has
 * the flag off.
 */
class CloserLookQuestionTest {

    private val scope = SaverScope { true }

    private fun roundTrip(state: TourState): TourState {
        val saved = with(TourState.Saver) { scope.save(state) }!!
        return TourState.Saver.restore(saved)!!
    }

    private fun onTheQuestion(): TourState = TourState().apply {
        start(settingsWalkAndQuestion())
        repeat(SETTINGS_TOUR.size) { next() }
    }

    // Its place

    @Test
    fun `a first install's tutorial ends on the question, after the walk round Settings`() {
        val tour = tourFor(seenVersionCode = 0, buildVersionCode = 92, settingsWalk = true)
        assertEquals(CLOSER_LOOK_QUESTION, tour.last())
        assertEquals(SETTINGS_TOUR.last(), tour[tour.lastIndex - 1])
        assertEquals(1, tour.count { it == CLOSER_LOOK_QUESTION })
        assertNull("the question points at nothing", CLOSER_LOOK_QUESTION.targetId)
        assertEquals("and is asked on the list of settings, where the walk ends", Tour.ROUTE_SETTINGS, CLOSER_LOOK_QUESTION.route)
    }

    @Test
    fun `without the flag nobody is asked, and somebody who has had the tutorial is not asked on an update`() {
        assertFalse(CLOSER_LOOK_QUESTION in tourFor(seenVersionCode = 0, buildVersionCode = 92, settingsWalk = false))
        assertFalse(CLOSER_LOOK_QUESTION in tourFor(seenVersionCode = 80, buildVersionCode = 92, settingsWalk = true))
        assertFalse("the tour started from About is the tour of the app alone", CLOSER_LOOK_QUESTION in tourAll())
    }

    @Test
    fun `the walk asked for from the welcome back page is the four groups, and ends without the question`() {
        // The question is the tutorial's. Somebody who asks the page for a walk round Settings
        // has asked for that much, and is brought back to the page when it is done.
        val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("the page's walk is not the four groups alone", "showFromWelcome(SETTINGS_WALK, SETTINGS_TOUR)" in activity)
        assertFalse("the page's walk still ends on the question", "showFromWelcome(SETTINGS_WALK, settingsWalkAndQuestion())" in activity)
        assertFalse(CLOSER_LOOK_QUESTION in SETTINGS_TOUR)
        // The tutorial's walk is as it was.
        assertEquals(SETTINGS_TOUR + CLOSER_LOOK_QUESTION, settingsWalkAndQuestion())
        // What the page asks for is kept by id across a rotation.
        val show = WelcomeShow().apply { ask(SETTINGS_WALK, SETTINGS_TOUR) }
        assertEquals(SETTINGS_TOUR, show.stops)
    }

    @Test
    fun `only the question offers a tour, and it offers the closer look`() {
        assertEquals(SETTINGS_CLOSER_LOOK, tourOfferedBy(CLOSER_LOOK_QUESTION))
        for (stop in TOUR_STOPS + SETTINGS_TOUR + SETTINGS_CLOSER_LOOK) assertNull(stop.id, tourOfferedBy(stop))
    }

    @Test
    fun `the question has an id of its own, and its words are held back from translation`() {
        val others = TOUR_STOPS + SETTINGS_TOUR + SETTINGS_CLOSER_LOOK + NEW_THINGS.flatMap { it.stops }
        assertFalse(CLOSER_LOOK_QUESTION.id in others.map { it.id })
        assertEquals("it does not come back after a rotation", CLOSER_LOOK_QUESTION, ALL_TOUR_STOPS.firstOrNull { it.id == CLOSER_LOOK_QUESTION.id })

        val strings = File("src/main/res/values/strings-ot.xml").readLines()
        for (id in listOf(CLOSER_LOOK_QUESTION.title, CLOSER_LOOK_QUESTION.body)) {
            val name = R.string::class.java.fields.first { it.getInt(null) == id }.name
            val line = strings.firstOrNull { "name=\"$name\"" in it }
            assertNotNull("$name is not in strings-ot.xml", line)
            assertTrue("$name can be translated", "translatable=\"false\"" in line!!)
            assertFalse("$name has an exclamation mark", '!' in line.substringAfter('>'))
        }
    }

    // What its bubble is, and the one before it

    @Test
    fun `Done on the last stop of the walk brings the question, and the walk counts four stops as before`() {
        val state = TourState()
        state.start(settingsWalkAndQuestion())
        repeat(SETTINGS_TOUR.size - 1) { state.next() }
        assertEquals(SETTINGS_TOUR.last(), state.current)
        assertTrue("the last stop that points at something reads Done", state.onLastStep)
        assertFalse(state.asking)
        assertEquals(SETTINGS_TOUR.size, state.stops.count { it.targetId != null })

        state.next()

        assertTrue(state.running)
        assertEquals(CLOSER_LOOK_QUESTION, state.current)
        assertTrue(state.asking)
        assertFalse("the question is not a step", state.onLastStep)
    }

    @Test
    fun `the welcome is an offer and not a question about what follows`() {
        val state = TourState()
        state.start(tourFor(seenVersionCode = 0, buildVersionCode = 92, settingsWalk = true))
        assertEquals("welcome", state.current!!.id)
        assertFalse(state.asking)
        assertFalse(state.onLastStep)
    }

    @Test
    fun `a tour with no question ends on its last stop as it always did`() {
        val state = TourState()
        state.start(SETTINGS_TOUR)
        repeat(SETTINGS_TOUR.size - 1) { state.next() }
        assertTrue(state.onLastStep)
        state.next()
        assertFalse(state.running)
    }

    // Its two answers

    @Test
    fun `Not now ends the tour`() {
        val state = onTheQuestion()
        state.stop()
        assertFalse(state.running)
    }

    @Test
    fun `Show me starts the tour of the settings from its first stop`() {
        val state = onTheQuestion()

        state.next()

        assertTrue(state.running)
        assertEquals(SETTINGS_CLOSER_LOOK, state.stops)
        assertEquals(0, state.index)
        assertEquals(SETTINGS_CATEGORIES.first { it !in CLOSER_LOOK_LEFT_OUT }, state.current!!.route)
        assertFalse(state.asking)
    }

    @Test
    fun `Back on the question goes back to the last stop of the walk, on the same screen`() {
        val state = onTheQuestion()
        assertNull("the question is asked over the list of settings", state.back())
        assertEquals(SETTINGS_TOUR.last(), state.current)
    }

    @Test
    fun `skipping the tutorial earlier does not ask`() {
        val state = TourState()
        state.start(settingsWalkAndQuestion())
        state.next()
        state.stop()
        assertFalse(state.running)
        assertNull(state.current)
    }

    @Test
    fun `the question has no next category to go to`() {
        assertNull(onTheQuestion().nextScreen)
    }

    // What a rotation restores

    @Test
    fun `a rotation on the question brings the question back`() {
        val restored = roundTrip(onTheQuestion())
        assertTrue(restored.running)
        assertEquals(CLOSER_LOOK_QUESTION, restored.current)
        assertTrue(restored.asking)
        restored.next()
        assertEquals("and Show me still leads into the settings", SETTINGS_CLOSER_LOOK, restored.stops)
    }

    @Test
    fun `the tutorial is marked as seen once the question is up, whatever the answer`() {
        // Somebody who says yes and leaves the app half way round the settings is not given the
        // whole tutorial again. It is a line in MainActivity, so this reads it there.
        val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("if (tourState.asking && !tourFromWelcome) setWalkthroughSeen(BuildConfig.VERSION_CODE)" in activity)
    }
}
