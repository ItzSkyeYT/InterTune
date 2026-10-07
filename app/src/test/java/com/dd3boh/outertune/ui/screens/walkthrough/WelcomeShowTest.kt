/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.runtime.saveable.SaverScope
import com.dd3boh.outertune.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the welcome back page has asked the tour for, between "Show me" and the first bubble. That
 * moment is short and two things went wrong in it: a card was ticked as seen before anybody knew
 * whether there was anything to show, and a rotation left neither a tour nor the page.
 */
class WelcomeShowTest {

    private val scope = SaverScope { true }

    private fun roundTrip(show: WelcomeShow): WelcomeShow {
        val saved = with(WelcomeShow.Saver) { scope.save(show) }!!
        return WelcomeShow.Saver.restore(saved)!!
    }

    private val history = NEW_THINGS.first { it.id == "history" }
    private val livingBlur = NEW_THINGS.first { it.id == "living_blur" }

    // Ticked only once it has been shown

    @Test
    fun `asking does not tick the card`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops)

        assertTrue(show.waiting)
        assertEquals(history.stops, show.stops)
        assertEquals("ticked before anything was shown", emptyList<String>(), show.lookedAt)
    }

    @Test
    fun `the card is ticked when the tour is up, and nothing is left waiting`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops)
        show.shown()

        assertEquals(listOf("history"), show.lookedAt)
        assertFalse(show.waiting)
        assertEquals(emptyList<TourStop>(), show.stops)
    }

    @Test
    fun `a card with nothing to show is not ticked`() {
        // Quick picks while the engine has fallen back to another row: the chips are not there,
        // the page says so, and the card must not then read as seen.
        val show = WelcomeShow()
        show.ask("quick_picks", NEW_THINGS.first { it.id == "quick_picks" }.stops)
        show.notShown()

        assertEquals(emptyList<String>(), show.lookedAt)
        assertFalse(show.waiting)
    }

    @Test
    fun `what was looked at before stays ticked whatever comes of the next one`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops); show.shown()
        show.ask(SETTINGS_WALK, SETTINGS_TOUR); show.notShown()
        show.looked("widget")

        assertEquals(listOf("history", "widget"), show.lookedAt)
    }

    @Test
    fun `looking twice ticks once`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops); show.shown()
        show.ask(history.id, history.stops); show.shown()

        assertEquals(listOf("history"), show.lookedAt)
    }

    @Test
    fun `opening the page afresh forgets the ticks and anything still asked for`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops); show.shown()
        show.ask(livingBlur.id, livingBlur.stops)
        show.startOver()

        assertEquals(emptyList<String>(), show.lookedAt)
        assertFalse(show.waiting)
    }

    // A rotation between "Show me" and the first bubble

    @Test
    fun `a rotation before the first bubble keeps what was asked for`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops); show.shown()
        show.ask(livingBlur.id, livingBlur.stops)

        val restored = roundTrip(show)

        assertTrue("the tour that was on its way is gone", restored.waiting)
        assertEquals(livingBlur.stops, restored.stops)
        assertEquals(listOf("history"), restored.lookedAt)
        restored.shown()
        assertEquals("and it is ticked under the card it was asked from", listOf("history", "living_blur"), restored.lookedAt)
    }

    @Test
    fun `a rotation with nothing asked for brings nothing back to start`() {
        val show = WelcomeShow()
        show.ask(history.id, history.stops); show.shown()

        val restored = roundTrip(show)

        assertFalse(restored.waiting)
        assertEquals(listOf("history"), restored.lookedAt)
    }

    @Test
    fun `nothing asked and nothing looked at saves nothing`() {
        assertNull(with(WelcomeShow.Saver) { scope.save(WelcomeShow()) })
    }

    // Where a stop on Home is

    @Test
    fun `a stop on Home is gone to on Home's own screen and at its top`() {
        assertEquals(Screens.Home.route, Tour.ROUTE_HOME)

        // The fault: the page went to the screen the app opens on, which is the default tab and
        // need not be Home, and left Home scrolled where it was, where what is pointed at, an
        // item of a lazy list, does not exist. Both are a line each in MainActivity, so this
        // reads them there.
        val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val goHome = activity.substringAfter("val goHome = {").substringBefore("// A tour from the page")
        assertTrue("going Home does not go to Home's route", "navigateToNavTab(navController, Tour.ROUTE_HOME" in goHome)
        assertTrue("going Home does not send it to the top", "set(\"scrollToTop\", true)" in goHome)
        assertTrue("a stop with no route is not sent Home", "if (route == null) goHome()" in activity)
    }
}
