/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.navigation

import androidx.compose.animation.EnterExitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * With Back animations on, the screen being left slides off in one piece over the one under it,
 * and a screen has no fill of its own: the screen under showed through every gap of the one going
 * away (GitHub #134, a tester on a Pixel and an A53). The screen going away now carries the page
 * colour with it, and only that screen, only with Back animations on.
 *
 * The decision is plain; the rest reads the source, as ScreenBackHandlerTest does, for the two
 * things that keep it true on every screen: one place that makes destinations, and the page colour.
 */
class LeavingScreenFillTest {

    private val graph = File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText()
    private val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    @Test
    fun theScreenGoingAwayHasAFillWithBackAnimationsOn() {
        assertTrue(fillsWhileLeaving(backAnimations = true, state = EnterExitState.PostExit))
    }

    @Test
    fun aScreenStayingOrComingInHasNone() {
        assertFalse(fillsWhileLeaving(backAnimations = true, state = EnterExitState.Visible))
        assertFalse(fillsWhileLeaving(backAnimations = true, state = EnterExitState.PreEnter))
    }

    @Test
    fun withBackAnimationsOffNoScreenHasOne() {
        for (state in EnterExitState.values()) {
            assertFalse("$state", fillsWhileLeaving(backAnimations = false, state = state))
        }
    }

    @Test
    fun everyDestinationIsMadeByTheOneHelperThatDrawsTheFill() {
        val calls = Regex("""(?<![A-Za-z.])composable\(""").findAll(graph).count()
        assertEquals("destinations made with composable( outside screen()", 1, calls)
        val screen = graph.substringAfter("private fun NavGraphBuilder.screen(")
        assertTrue("screen() does not draw the fill", "pageFillWhileLeaving(transition)" in screen.substringBefore("TourTargets"))
    }

    @Test
    fun theFillIsThePageColourTheAppIsDrawnOn() {
        val fill = graph.substringAfter("private fun Modifier.pageFillWhileLeaving").substringBefore("\n}\n")
        assertTrue("the fill is not the surface colour", "MaterialTheme.colorScheme.surface" in fill)
        assertTrue("MainActivity's own background is no longer the surface colour", ".background(MaterialTheme.colorScheme.surface)" in activity)
    }

    @Test
    fun theNavHostIsToldWhetherBackAnimationsAreOn() {
        assertTrue("MainActivity does not provide the setting", "LocalBackAnimations provides backAnimations" in activity)
        assertTrue("the setting provided is not the Back animations switch", "val (backAnimations) = rememberPreference(BackAnimationsKey, defaultValue = true)" in activity)
    }
}
