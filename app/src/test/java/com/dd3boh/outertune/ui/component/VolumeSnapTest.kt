/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The volume bar in the player's menu rests on the line where boost begins when a drag comes near it, and ticks once
 * as it lands (VolumeSnap, Unreleased.HAPTICS_MORE; #general, 18 Nov 2026). The pull is plain Kotlin and tested as
 * such; the bar is Compose and read from its source, as MoreHapticsTest reads the player's.
 */
class VolumeSnapTest {

    private val max = 4f   // MAX_PLAYER_VOLUME: the bar goes to 400%, the line at 100%

    /** What the bar shows at each of [values], the drag's own value move by move from a start at [from], and its ticks. */
    private fun walk(from: Float, values: List<Float>): Pair<List<Float>, Int> {
        var held = VolumeSnap.holds(from, max, held = false, on = true)
        var ticks = 0
        val shown = values.map { value ->
            val was = held
            held = VolumeSnap.holds(value, max, was, on = true)
            if (VolumeSnap.lands(was, held)) ticks++
            VolumeSnap.shown(value, held)
        }
        return shown to ticks
    }

    /** A drag of [steps] equal moves from [from] to [to]: what the bar shows at each, and its ticks. */
    private fun drag(from: Float, to: Float, steps: Int = 200) = walk(from, (1..steps).map { from + (to - from) * it / steps })

    @Test
    fun `just above the line the bar rests on it, a few percent of the bar`() {
        assertEquals(0.03f, VolumeSnap.ZONE)
        for (volume in listOf(1f, 1.1f, 1.11f)) {
            assertTrue("$volume", VolumeSnap.holds(volume, max, held = false, on = true))
            assertEquals(1f, VolumeSnap.shown(volume, held = true))
        }
        // Past the zone, the bar is where the finger put it.
        assertFalse(VolumeSnap.holds(1.2f, max, held = false, on = true))
        assertEquals(1.2f, VolumeSnap.shown(1.2f, held = false))
    }

    @Test
    fun `below the line the bar follows the finger, so 88% to 99% can still be set`() {
        for (percent in 80..99) {
            val volume = percent / 100f
            assertFalse("$percent%", VolumeSnap.holds(volume, max, held = false, on = true))
            assertEquals(volume, VolumeSnap.shown(volume, held = false))
        }
        // A drag from a saved 90% that moves down first goes down, never up to 100%.
        assertEquals(listOf(0.89f) to 0, walk(0.9f, listOf(0.89f)))
    }

    @Test
    fun `a slow drag goes through the line and out the other side, held only above it`() {
        val (up, upTicks) = drag(0.6f, 1.6f)
        assertEquals(1.6f, up.last(), 1e-4f)
        val held = up.count { it == 1f }
        // 0.15 of the drag's 1.0 (the zone and the margin past it) rests on the line, no more.
        assertTrue("held for $held of 200 moves", held in 26..34)
        // And never back below the line once past it, on the way up.
        val firstAbove = up.indexOfFirst { it > 1f }
        assertTrue(up.drop(firstAbove).all { it > 1f })
        assertEquals(1, upTicks)
        // Coming back down from boost it rests on the line, then follows the finger below it, step by step.
        val (down, downTicks) = drag(1.6f, 0.6f)
        val lastHeld = down.lastIndexOf(1f)
        assertTrue(lastHeld > 0)
        val below = down.drop(lastHeld + 1)
        assertTrue(below.all { it < 1f })
        assertTrue(1f - below.first() < 0.01f)
        assertTrue(below.zipWithNext().all { (a, b) -> b < a && a - b < 0.01f })
        assertEquals(0.6f, down.last(), 1e-4f)
        assertEquals(1, downTicks)
    }

    @Test
    fun `a finger resting at the zone's edge neither flips the volume nor ticks on every other move`() {
        assertEquals(VolumeSnap.ZONE / 4, VolumeSnap.MARGIN)
        // At the zone's top, 112%, coming down from boost: moves of a fraction of a pixel across the edge. It used to
        // go 100%, 112%, 100%... with a tick each time it came back.
        val (top, topTicks) = walk(1.3f, List(100) { if (it % 2 == 0) 1.122f else 1.118f })
        assertEquals(1, topTicks)
        assertTrue(top.drop(1).all { it == 1f })
        // At the line itself, from below: one tick, then the bar stays within a hair of 100%.
        val (line, lineTicks) = walk(0.95f, List(100) { if (it % 2 == 0) 1.001f else 0.999f })
        assertEquals(1, lineTicks)
        assertTrue(line.all { it in 0.999f..1f })
    }

    @Test
    fun `it leaves the line only a margin past the zone, and ticks again only when it comes back in`() {
        // Up: still resting at 114%, gone at 116%, and back in only at the zone's own edge, with its tick.
        assertEquals(listOf(1f, 1.16f, 1.14f, 1f) to 1, walk(1f, listOf(1.14f, 1.16f, 1.14f, 1.12f)))
        // Down: below the line the bar follows the finger; it has left at 96%, and lands again at 100%.
        assertEquals(listOf(0.98f, 0.96f, 0.98f, 1f) to 1, walk(1f, listOf(0.98f, 0.96f, 0.98f, 1f)))
        assertFalse(VolumeSnap.lands(was = true, now = true))
        assertFalse(VolumeSnap.lands(was = true, now = false))
        assertTrue(VolumeSnap.lands(was = false, now = true))
    }

    @Test
    fun `without the flag, and on a bar that stops at 100%, nothing snaps`() {
        assertFalse(VolumeSnap.holds(1.1f, max, held = false, on = false))
        assertFalse(VolumeSnap.holds(1f, max, held = true, on = false))
        // Saver stops the bar at 1: no boost, no line.
        assertFalse(VolumeSnap.holds(0.98f, 1f, held = false, on = true))
        assertFalse(VolumeSnap.holds(1f, 1f, held = false, on = true))
    }

    // ---- where it is wired ----

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `the volume bar keeps its old way without the flag, and rests and ticks with it`() {
        val bar = source("ui/component/BigSeekBar.kt")
        // Without the flag: as it always was.
        assertTrue("if (!Unreleased.HAPTICS_MORE) {\n                        onProgressChange((progressProvider() + delta).coerceIn(0f, maxProgress))" in bar)
        // With it: the drag's own value, whether it rests on the line, one tick on landing.
        assertTrue("onDragStart = { dragged = progressProvider(); held = VolumeSnap.holds(dragged, maxProgress, held = false) }," in bar)
        assertTrue("onDragEnd = { dragged = Float.NaN; held = false }," in bar)
        assertTrue("onDragCancel = { dragged = Float.NaN; held = false }," in bar)
        // Whether it rests is the drag's own, not read back from the volume, which comes back a frame late.
        assertTrue("val was = held\n                    held = VolumeSnap.holds(dragged, maxProgress, was)" in bar)
        assertTrue("if (VolumeSnap.lands(was, held)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)" in bar)
        assertTrue("onProgressChange(VolumeSnap.shown(dragged, held))" in bar)
        // It is the player menu's volume bar.
        assertTrue("BigSeekBar(" in source("ui/menu/PlayerMenu.kt"))
    }
}
