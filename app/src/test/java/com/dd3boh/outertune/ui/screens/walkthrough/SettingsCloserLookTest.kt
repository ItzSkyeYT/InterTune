/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.runtime.saveable.SaverScope
import com.dd3boh.outertune.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The closer look at the settings: a tour that goes into each screen on the Settings list and
 * points at the settings there.
 *
 * Most of what can go wrong with it is a list that has drifted from the screens: a category that
 * was added to Settings and never to the tour, a stop whose row lost its mark, a title that no
 * longer says what the row says. Those are read from the source here, because nothing else would
 * notice: a stop with nothing to point at is quietly left out when the tour runs.
 */
class SettingsCloserLookTest {

    private val scope = SaverScope { true }
    private val main = File("src/main/java/com/dd3boh/outertune")
    private val settingsSources = File(main, "ui/screens/settings").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun roundTrip(state: TourState): TourState {
        val saved = with(TourState.Saver) { scope.save(state) }!!
        return TourState.Saver.restore(saved)!!
    }

    /** The name a string is written under, since a test has no resources to read its text from. */
    private fun stringName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    /** The constants of [Tour] by their value: the stops hold the value, the screens write the name. */
    private val targetNames: Map<String, String> = Regex("""const val (\w+) = "([^"]+)"""")
        .findAll(File(main, "ui/screens/walkthrough/Tour.kt").readText())
        .associate { it.groupValues[2] to it.groupValues[1] }

    @After
    fun clearScreens() {
        SETTINGS_CATEGORIES.forEach { route -> while (TourTargets.drawn(route)) TourTargets.screenLeft(route) }
    }

    // The list of stops

    @Test
    fun `the categories are the screens of the Settings list, in the order they are on it`() {
        // The four cards are written in SettingsScreen in the order they are stacked, so the order
        // of the rows in the file is the order on screen.
        val onScreen = Regex("""navController\.navigate\("(settings/[^"]+)"\)""")
            .findAll(File(main, "ui/screens/settings/SettingsScreen.kt").readText())
            .map { it.groupValues[1] }.toList()
        assertTrue("the Settings list was not read", onScreen.size >= 10)
        assertEquals(onScreen, SETTINGS_CATEGORIES)
    }

    @Test
    fun `every category has a stop, or is left out on purpose and says why`() {
        val toured = SETTINGS_CLOSER_LOOK.mapNotNull { it.route }.toSet()
        for (route in SETTINGS_CATEGORIES) {
            val why = CLOSER_LOOK_LEFT_OUT[route]
            if (route in toured) assertNull("$route has stops and is also listed as left out", why)
            else assertTrue("$route has no stop and is not listed as left out", !why.isNullOrBlank())
        }
        assertTrue("left out, and not a category: ${CLOSER_LOOK_LEFT_OUT.keys - SETTINGS_CATEGORIES.toSet()}", SETTINGS_CATEGORIES.containsAll(CLOSER_LOOK_LEFT_OUT.keys))
        assertTrue("a tour of the settings with most of them left out", toured.size >= SETTINGS_CATEGORIES.size - 3)
    }

    @Test
    fun `every stop points at a setting on one of those screens`() {
        assertTrue(SETTINGS_CLOSER_LOOK.isNotEmpty())
        for (stop in SETTINGS_CLOSER_LOOK) {
            assertNotNull("${stop.id} points at nothing", stop.targetId)
            assertTrue("${stop.id} is on ${stop.route}, which the Settings list does not lead to", stop.route in SETTINGS_CATEGORIES)
        }
    }

    @Test
    fun `the tour takes the categories in the Settings list's order, each in one go`() {
        val order = SETTINGS_CLOSER_LOOK.map { it.route }.fold(emptyList<String?>()) { seen, route -> if (seen.lastOrNull() == route) seen else seen + route }
        assertEquals(SETTINGS_CATEGORIES.filter { it !in CLOSER_LOOK_LEFT_OUT }, order)
    }

    @Test
    fun `a category is a handful of stops, not the whole screen`() {
        for ((route, stops) in SETTINGS_CLOSER_LOOK.groupBy { it.route }) {
            assertTrue("$route has ${stops.size} stops", stops.size in 1..8)
        }
    }

    @Test
    fun `no two stops share an id, in this tour or with any other`() {
        val every = TOUR_STOPS + SETTINGS_TOUR + SETTINGS_CLOSER_LOOK + NEW_THINGS.flatMap { it.stops }
        val twice = every.groupBy { it.id }.filter { (_, same) -> same.distinct().size > 1 }.keys
        assertEquals(emptySet<String>(), twice)
        // A rotation brings the tour back by its ids, from this list.
        for (stop in SETTINGS_CLOSER_LOOK) {
            assertEquals("${stop.id} does not come back after a rotation", stop, ALL_TOUR_STOPS.firstOrNull { it.id == stop.id })
        }
    }

    @Test
    fun `no setting is pointed at twice`() {
        val twice = SETTINGS_CLOSER_LOOK.groupBy { it.targetId }.filter { it.value.size > 1 }.keys
        assertEquals(emptySet<String?>(), twice)
    }

    // The two ends: a stop, and the row it points at

    /** Where [target] is marked, as the file and the line of its tourTarget. */
    private fun marks(target: String): List<Pair<File, Int>> {
        val name = targetNames[target] ?: return emptyList()
        val mark = Regex("""tourTarget\(Tour\.$name\)""")
        return settingsSources.flatMap { file -> file.readLines().withIndex().filter { mark.containsMatchIn(it.value) }.map { file to it.index } }
    }

    @Test
    fun `every stop's setting is marked on a settings screen, once`() {
        for (stop in SETTINGS_CLOSER_LOOK) {
            assertTrue("${stop.id}: ${stop.targetId} is not a constant of Tour", stop.targetId in targetNames)
            assertEquals("${stop.id}: rows marked Tour.${targetNames[stop.targetId]}", 1, marks(stop.targetId!!).size)
        }
    }

    @Test
    fun `a stop is titled with the string its row is titled with`() {
        for (stop in SETTINGS_CLOSER_LOOK) {
            val (file, line) = marks(stop.targetId!!).singleOrNull() ?: continue
            val title = stringName(stop.title)
            // The row's own title follows its mark within a few lines, or is a value read just
            // above the row where the title is used twice.
            val row = file.readLines().drop(maxOf(0, line - 8)).take(16).joinToString("\n")
            assertTrue("${stop.id} is titled $title, and ${file.name}:${line + 1} is not", Regex("""R\.string\.$title\b""").containsMatchIn(row))
        }
    }

    @Test
    fun `its texts are held back from translation with the rest of this work`() {
        val strings = File("src/main/res/values/strings-ot.xml").readLines()
        val texts = SETTINGS_CLOSER_LOOK.map { stringName(it.body) }.toSet() + listOf("tour_closer_next_category", "tour_closer_entry")
        for (name in texts) {
            val line = strings.firstOrNull { "name=\"$name\"" in it }
            assertNotNull("$name is not in strings-ot.xml", line)
            assertTrue("$name can be translated", "translatable=\"false\"" in line!!)
            assertFalse("$name has an exclamation mark", '!' in line.substringAfter('>'))
            assertFalse("$name has an em dash", '—' in line)
        }
        // One sentence a stop, two at most: a bubble is not a page.
        for (stop in SETTINGS_CLOSER_LOOK) {
            val text = strings.first { "name=\"${stringName(stop.body)}\"" in it }.substringAfter('>').substringBefore("</string>")
            assertTrue("${stop.id} runs to ${text.length} characters", text.length in 30..230)
        }
    }

    // Moving through it

    private fun touring(): TourState = TourState().apply { start(SETTINGS_CLOSER_LOOK) }

    @Test
    fun `Next goes through a category and on into the one after`() {
        val state = touring()
        val first = SETTINGS_CLOSER_LOOK.first().route
        val inFirst = SETTINGS_CLOSER_LOOK.count { it.route == first }
        repeat(inFirst - 1) { state.next() }
        assertEquals(first, state.current!!.route)
        state.next()
        assertEquals(SETTINGS_CLOSER_LOOK[inFirst], state.current)
        assertTrue(state.current!!.route != first)
    }

    @Test
    fun `Back from the first stop of a category says to go back to the screen before`() {
        val state = touring()
        val first = SETTINGS_CLOSER_LOOK.first().route
        val inFirst = SETTINGS_CLOSER_LOOK.count { it.route == first }
        repeat(inFirst) { state.next() }

        val backTo = state.back()

        assertEquals(first, backTo?.route)
        assertEquals(SETTINGS_CLOSER_LOOK[inFirst - 1], state.current)
    }

    @Test
    fun `Next category leaves the rest of this one for the first stop of the next`() {
        val state = touring()
        val first = state.current!!.route
        val second = SETTINGS_CLOSER_LOOK.first { it.route != first }
        assertEquals(second, state.nextScreen)

        state.skipScreen()

        assertEquals(second, state.current)
        assertEquals("the counter goes on from where that stop is in the whole tour", SETTINGS_CLOSER_LOOK.indexOf(second), state.index)
    }

    @Test
    fun `the last category has no next one to go to, and neither has any other tour`() {
        val state = touring()
        repeat(SETTINGS_CLOSER_LOOK.size - 1) { state.next() }
        assertNull(state.nextScreen)
        state.skipScreen()
        assertEquals("nothing to skip to, nothing done", SETTINGS_CLOSER_LOOK.last(), state.current)

        // The tutorial goes from Home into Settings, and a new thing from the list into a screen:
        // neither is a tour by categories, and neither grows the button.
        val tutorial = TourState().apply { start(SETTINGS_TOUR) }
        assertNull(tutorial.nextScreen)
        val newThing = TourState().apply { start(NEW_THINGS.first { it.id == "spatial_audio" }.stops) }
        assertNull(newThing.nextScreen)
        newThing.next()
        assertNull(newThing.nextScreen)
    }

    // What a rotation restores

    @Test
    fun `a rotation in the middle of the settings keeps the stop, the screen it is on and the count`() {
        val state = touring()
        repeat(7) { state.next() }
        val restored = roundTrip(state)
        assertTrue(restored.running)
        assertEquals(state.current, restored.current)
        assertEquals(7, restored.index)
        assertEquals(SETTINGS_CLOSER_LOOK, restored.stops)
        assertEquals(state.nextScreen, restored.nextScreen)
    }

    // Held back

    @Test
    fun `the entry under About and the screen behind it are there only with the flag`() {
        val about = File(main, "ui/screens/settings/AboutScreen.kt").readText()
        val flagged = about.substringAfter("if (Unreleased.WELCOME_BACK) {").substringBefore("R.string.help_bug_report_action")
        assertTrue("About has no entry for the tour of the settings", "R.string.tour_closer_entry" in about)
        assertTrue("the entry is outside the flag", "R.string.tour_closer_entry" in flagged)
        val graph = File(main, "ui/navigation/AppNavGraph.kt").readLines()
        val screen = graph.indexOfFirst { "screen(\"settings_tour\"" in it }
        assertTrue("the entry leads nowhere", screen > 0)
        assertTrue("its screen is outside the flag", "if (Unreleased.WELCOME_BACK)" in graph[screen - 1])
    }

    @Test
    fun `the tour leaves Settings when it ends, from whichever screen it is on`() {
        val activity = File(main, "MainActivity.kt").readText()
        val finish = activity.substringAfter("onFinish = {").substringBefore("SnackbarHost(")
        assertTrue("ending in a category leaves somebody in it", "popBackStack(Tour.ROUTE_SETTINGS, inclusive = true)" in finish)
    }
}
