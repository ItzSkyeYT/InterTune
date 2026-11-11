/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.ui.component.SwipeToRemove.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.tan

/**
 * #141 (Ti-Die, 0.12.9, 11 Nov 2026): a song swiped out of the queue left it before the finger let go,
 * with no way to take it back, there was nothing behind the row, and "I tend to remove songs by
 * accident". The rule that decides it is plain numbers, held here: a song goes only when the finger
 * lets go, far enough along the row, and clearly sideways.
 */
class SwipeToRemoveTest {

    /** A phone: 3 px to the dp, 1080 px wide, a touch slop of 8 dp. */
    private val density = 3f
    private val width = 1080f
    private val slop = 24f
    private val distance = SwipeToRemove.distance(width, density)

    /** A move [along] the row that stays within [degrees] of the horizontal's own line, in px. */
    private fun across(along: Float, degrees: Double) = (along * tan(Math.toRadians(degrees))).toFloat()

    @Test
    fun `the distance is the middle of the row, and 200 dp at the most`() {
        assertEquals(540f, distance, 0.001f)
        // A tablet's row, or the queue on its side: 2400 px is 800 dp, and half would be 400.
        assertEquals(600f, SwipeToRemove.distance(2400f, density), 0.001f)
        // A narrow row is still the middle of it.
        assertEquals(300f, SwipeToRemove.distance(600f, density), 0.001f)
    }

    @Test
    fun `nothing is removed until the finger lets go`() {
        // All the way across, flat, and still down: the row waits, with the bin showing.
        assertFalse(SwipeToRemove.removes(dx = 1000f, dy = 0f, distance = distance, released = false))
        assertFalse(SwipeToRemove.removes(dx = -1000f, dy = 0f, distance = distance, released = false))
        // The same, let go: it goes.
        assertTrue(SwipeToRemove.removes(dx = 1000f, dy = 0f, distance = distance, released = true))
        assertTrue(SwipeToRemove.removes(dx = -1000f, dy = 0f, distance = distance, released = true))
    }

    @Test
    fun `dragged back short of the line before letting go, it stays`() {
        // Past the line (the bin is full), then back to a third of the row, then let go.
        assertTrue(SwipeToRemove.ready(dx = 700f, dy = 0f, distance = distance))
        assertFalse(SwipeToRemove.ready(dx = 360f, dy = 0f, distance = distance))
        assertFalse(SwipeToRemove.removes(dx = 360f, dy = 0f, distance = distance, released = true))
        // And back to where it began.
        assertFalse(SwipeToRemove.removes(dx = 0f, dy = 0f, distance = distance, released = true))
    }

    @Test
    fun `the line is the middle of the row, either way, and on it counts`() {
        assertTrue(SwipeToRemove.removes(dx = distance, dy = 0f, distance = distance, released = true))
        assertTrue(SwipeToRemove.removes(dx = -distance, dy = 0f, distance = distance, released = true))
        assertFalse(SwipeToRemove.removes(dx = distance - 1f, dy = 0f, distance = distance, released = true))
        assertFalse(SwipeToRemove.removes(dx = -(distance - 1f), dy = 0f, distance = distance, released = true))
    }

    @Test
    fun `a short slip of the thumb never removes, however quick`() {
        // 40 dp and 120 dp, flat, let go: not a row's half. There is no speed in the rule at all.
        assertFalse(SwipeToRemove.removes(dx = 120f, dy = 0f, distance = distance, released = true))
        assertFalse(SwipeToRemove.removes(dx = 360f, dy = 0f, distance = distance, released = true))
        assertFalse(SwipeToRemove.removes(dx = -360f, dy = 0f, distance = distance, released = true))
    }

    @Test
    fun `a swipe that wanders off the horizontal is not one`() {
        val along = 700f
        // 25 degrees: a hand's own wobble. 30: the edge, and it counts. 35, 45, 60: no.
        assertTrue(SwipeToRemove.removes(along, across(along, 25.0), distance, released = true))
        assertTrue(SwipeToRemove.removes(along, across(along, 29.9), distance, released = true))
        assertFalse(SwipeToRemove.removes(along, across(along, 30.5), distance, released = true))
        assertFalse(SwipeToRemove.removes(along, across(along, 35.0), distance, released = true))
        assertFalse(SwipeToRemove.removes(along, along, distance, released = true))
        assertFalse(SwipeToRemove.removes(along, across(along, 60.0), distance, released = true))
        // Up or down, left or right, the same.
        assertFalse(SwipeToRemove.removes(-along, -across(along, 35.0), distance, released = true))
        assertTrue(SwipeToRemove.removes(-along, -across(along, 25.0), distance, released = true))
        assertFalse(SwipeToRemove.removes(along, -across(along, 35.0), distance, released = true))
        // The bin is dimmed for it too: it would not go.
        assertFalse(SwipeToRemove.ready(along, across(along, 35.0), distance))
    }

    @Test
    fun `a scroll never removes, let go or not`() {
        // 20 dp across, 300 dp down: a scroll, and then some.
        for (released in listOf(false, true)) {
            assertFalse(SwipeToRemove.removes(dx = 60f, dy = 900f, distance = distance, released = released))
            assertFalse(SwipeToRemove.removes(dx = 0f, dy = 900f, distance = distance, released = released))
            assertFalse(SwipeToRemove.removes(dx = -60f, dy = -900f, distance = distance, released = released))
        }
    }

    @Test
    fun `a touch is judged by its first move past the slop, along or across`() {
        // Within the slop both ways: wait.
        assertEquals(Start.WAIT, SwipeToRemove.start(dx = 10f, dy = 5f, slop = slop))
        assertEquals(Start.WAIT, SwipeToRemove.start(dx = 0f, dy = 0f, slop = slop))
        assertEquals(Start.WAIT, SwipeToRemove.start(dx = -23f, dy = 23f, slop = slop))
        // Along, and flat: a swipe, either way.
        assertEquals(Start.SWIPE, SwipeToRemove.start(dx = 30f, dy = 5f, slop = slop))
        assertEquals(Start.SWIPE, SwipeToRemove.start(dx = -30f, dy = -5f, slop = slop))
        assertEquals(Start.SWIPE, SwipeToRemove.start(dx = 24f, dy = 0f, slop = slop))
        // Across first, a scroll or a drag by the handle: never a swipe.
        assertEquals(Start.NOT_A_SWIPE, SwipeToRemove.start(dx = 5f, dy = 30f, slop = slop))
        assertEquals(Start.NOT_A_SWIPE, SwipeToRemove.start(dx = 0f, dy = -24f, slop = slop))
        // Along, but steep: the old box took these (under 45 degrees), and now they are the list's.
        assertEquals(Start.NOT_A_SWIPE, SwipeToRemove.start(dx = 30f, dy = 30f, slop = slop))
        assertEquals(Start.NOT_A_SWIPE, SwipeToRemove.start(dx = 30f, dy = 20f, slop = slop))
        assertEquals(Start.NOT_A_SWIPE, SwipeToRemove.start(dx = 30f, dy = 18f, slop = slop))
        assertEquals(Start.SWIPE, SwipeToRemove.start(dx = 30f, dy = 17f, slop = slop))
    }

    @Test
    fun `a touch that began as a scroll never turns into a swipe half way`() {
        // The finger goes down the row first and then runs along: the first judgement stands, since
        // it is made at the first move past the slop and the rest of the touch is not asked.
        val path = listOf(5f to 10f, 8f to 25f, 90f to 40f, 400f to 60f)
        val first = path.map { SwipeToRemove.start(it.first, it.second, slop) }.first { it != Start.WAIT }
        assertEquals(Start.NOT_A_SWIPE, first)
    }

    @Test
    fun `the bin is ready exactly when letting go would take the row`() {
        for (dx in listOf(-900f, -541f, -300f, 0f, 300f, 539f, 540f, 900f)) {
            for (dy in listOf(0f, 100f, 400f, 900f)) {
                assertEquals(
                    "$dx, $dy",
                    SwipeToRemove.removes(dx, dy, distance, released = true),
                    SwipeToRemove.ready(dx, dy, distance)
                )
            }
        }
    }

    // The part that is not a number: the swipe box asks for the row to go in one place, once the finger
    // has let go and the row has slid out, and the queue gives it the bin and no other way.
    private val box = File("src/main/java/com/dd3boh/outertune/ui/component/SwipeToRemove.kt").readText()
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `the row is asked to go only after the finger let go, once, and never in the middle of a drag`() {
        val gesture = box.substringAfter("awaitEachGesture {").substringBefore("// Behind the row")
        assertEquals("asked for in one place", 1, Regex("""currentOnRemove\(\)""").findAll(box).count())
        assertFalse("not in the gesture, only in what comes after the letting go", "currentOnRemove" in gesture)
        // The rule is asked with the letting go only at the up, which settles the row.
        assertEquals(1, Regex("""SwipeToRemove\.removes\(""").findAll(gesture).count())
        val up = gesture.substringAfter("if (up) {").substringBefore("return@awaitEachGesture")
        assertTrue("SwipeToRemove.removes(dx, dy, distance, released = true)" in up && "settle(" in up)
        // The drag itself only moves the row and lights the bin.
        val drag = gesture.substringAfter("offset = dx - along * slop")
        assertTrue("SwipeToRemove.ready(dx, dy, distance)" in drag)
        assertFalse("settle(" in drag)
    }

    @Test
    fun `the queue's rows use it, with the bin, and the Material swipe box that took a song before the finger let go is gone`() {
        assertFalse("SwipeToDismissBox" in queue)
        assertFalse("rememberSwipeToDismissBoxState" in queue)
        assertEquals(1, Regex("""SwipeToRemoveBox\(""").findAll(queue).count())
        assertTrue("the bin", "Icons.Rounded.Delete" in box)
        // The look of the swipe that adds a song to the queue: its colours and its dimming.
        assertTrue("MaterialTheme.colorScheme.primary" in box && "MaterialTheme.colorScheme.onPrimary" in box)
        assertTrue("if (ready) 1f else 0.6f" in box)
    }

    @Test
    fun `a swipe that is a scroll, a long press or a drag by the handle is left alone`() {
        // The touch is watched, never taken, until it is a swipe: nothing is consumed before that.
        val gesture = box.substringAfter("awaitEachGesture {").substringBefore("// Behind the row")
        val beforeSwipe = gesture.substringBefore("change.consume()")
        assertTrue("a move another took ends it", "change.isConsumed" in beforeSwipe)
        assertTrue("an up before the swipe is a tap or a long press, not ours", "if (up) return@awaitEachGesture" in beforeSwipe)
        assertTrue("a move across is the list's", "SwipeToRemove.Start.NOT_A_SWIPE -> return@awaitEachGesture" in beforeSwipe)
    }
}
