/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's own swipes leave the system's gesture strips to Android. Reaching for the notification shade at the top
 * edge closed the player, and reaching for the home bar at the bottom edge pulled the queue up, as well as doing what
 * Android does (testers, 15 Nov 2026).
 */
class SystemGestureStripTest {

    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/component/BottomSheet.kt").readText()

    @Test
    fun `a touch in the strip at the top or the bottom is the system's`() {
        // a 2992px window with a 96px strip at the top and a 120px one at the bottom
        assertTrue(inSystemGestureStrip(y = 3f, windowHeight = 2992f, top = 96f, bottom = 120f))
        assertTrue(inSystemGestureStrip(y = 95f, windowHeight = 2992f, top = 96f, bottom = 120f))
        assertTrue(inSystemGestureStrip(y = 2990f, windowHeight = 2992f, top = 96f, bottom = 120f))
        assertTrue(inSystemGestureStrip(y = 2873f, windowHeight = 2992f, top = 96f, bottom = 120f))
    }

    @Test
    fun `everywhere else it is the sheet's, as it always was`() {
        assertFalse(inSystemGestureStrip(y = 97f, windowHeight = 2992f, top = 96f, bottom = 120f))
        assertFalse(inSystemGestureStrip(y = 1500f, windowHeight = 2992f, top = 96f, bottom = 120f))
        assertFalse(inSystemGestureStrip(y = 2872f, windowHeight = 2992f, top = 96f, bottom = 120f))
    }

    @Test
    fun `a strip of no height is no strip`() {
        // the bars hidden, or three buttons for navigation: the whole window is the sheet's
        assertFalse(inSystemGestureStrip(y = 0f, windowHeight = 2992f, top = 0f, bottom = 0f))
        assertFalse(inSystemGestureStrip(y = 2991f, windowHeight = 2992f, top = 0f, bottom = 0f))
    }

    @Test
    fun `the sheet reads the system's gesture insets, and the status bar even while it is hidden`() {
        assertTrue("WindowInsets.systemGestures" in sheet)
        // in lean-back landscape the bars are hidden and the swipe from the top edge brings them back: still Android's
        assertTrue("WindowInsets.statusBarsIgnoringVisibility" in sheet)
        assertTrue("max(gestureInsets.getTop(this), statusBarInsets.getTop(this))" in sheet)
        assertTrue("gestureInsets.getBottom(this)" in sheet)
    }

    @Test
    fun `where a touch came down is decided before the drag looks at it, and all three of its ends honour it`() {
        val decide = sheet.indexOf("startedInGestureStrip[0] = at != null && inSystemGestureStrip(")
        val drag = sheet.indexOf("detectVerticalDragGestures(")
        assertTrue(decide in 0 until drag)
        // looked at in the first pass, which comes before the drag's
        assertTrue("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)" in sheet)
        val dragCalls = sheet.substring(drag).substringBefore(".clip(")
        assertTrue(Regex("""onVerticalDrag = \{ change, dragAmount ->\s+if \(startedInGestureStrip\[0]\) return@detectVerticalDragGestures""").containsMatchIn(dragCalls))
        assertTrue(Regex("""onDragCancel = \{\s+velocityTracker\.resetTracking\(\)\s+// Android took the gesture[^\n]*\n\s+if \(startedInGestureStrip\[0]\) return@detectVerticalDragGestures""").containsMatchIn(dragCalls))
        assertTrue(Regex("""velocityTracker\.resetTracking\(\)\s+if \(startedInGestureStrip\[0]\) return@detectVerticalDragGestures\s+state\.performFling""").containsMatchIn(dragCalls))
    }

    @Test
    fun `the position is the window's, whatever the sheet's offset`() {
        // the sheet is moved down while it is collapsed or being dragged: the touch is placed through its layout, in the window
        assertTrue("localToWindow(down.position)" in sheet)
        assertTrue(sheet.indexOf(".offset {") < sheet.indexOf(".onGloballyPositioned { sheetPlace[0] = it }"))
    }
}
