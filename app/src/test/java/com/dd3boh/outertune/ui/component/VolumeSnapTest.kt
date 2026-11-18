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

    /** A drag of [steps] equal moves from [from] to [to], as the bar's own value; what the bar shows at each. */
    private fun drag(from: Float, to: Float, steps: Int = 200): List<Float> =
        (1..steps).map { VolumeSnap.shown(from + (to - from) * it / steps, max, on = true) }

    @Test
    fun `near the line the bar rests on it, a few percent of the bar either side`() {
        assertEquals(0.03f, VolumeSnap.ZONE)
        assertEquals(1f, VolumeSnap.shown(1.1f, max, on = true))
        assertEquals(1f, VolumeSnap.shown(0.9f, max, on = true))
        assertEquals(1f, VolumeSnap.shown(1.11f, max, on = true))
        // Past the zone, the bar is where the finger put it.
        assertEquals(1.2f, VolumeSnap.shown(1.2f, max, on = true))
        assertEquals(0.8f, VolumeSnap.shown(0.8f, max, on = true))
    }

    @Test
    fun `a slow drag goes through the line and out the other side, held only inside the zone`() {
        val shown = drag(0.6f, 1.6f)
        assertTrue(1f in shown)
        assertEquals(1.6f, shown.last(), 1e-4f)
        val held = shown.count { it == 1f }
        // 0.24 of the drag's 1.0 lies in the zone: about a quarter of the moves rest on the line, no more.
        assertTrue("held for $held of 200 moves", held in 44..52)
        // And never back below the line once past it, on the way up.
        val firstAbove = shown.indexOfFirst { it > 1f }
        assertTrue(shown.drop(firstAbove).all { it > 1f })
    }

    @Test
    fun `one tick as it lands, none while it rests there or leaves`() {
        var before = 0.6f
        var ticks = 0
        for (now in drag(0.6f, 1.6f)) {
            if (VolumeSnap.lands(before, now)) ticks++
            before = now
        }
        assertEquals(1, ticks)
        assertFalse(VolumeSnap.lands(1f, 1f))
        assertFalse(VolumeSnap.lands(1f, 1.2f))
    }

    @Test
    fun `without the flag, and on a bar that stops at 100%, nothing snaps`() {
        assertEquals(1.1f, VolumeSnap.shown(1.1f, max, on = false))
        // Saver stops the bar at 1: no boost, no line.
        assertEquals(0.98f, VolumeSnap.shown(0.98f, 1f, on = true))
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
        // With it: the drag's own value, the line's pull, one tick on landing.
        assertTrue("onDragStart = { dragged = progressProvider(); shown = dragged }," in bar)
        assertTrue("onDragEnd = { dragged = Float.NaN; shown = Float.NaN }," in bar)
        assertTrue("val now = VolumeSnap.shown(dragged, maxProgress)" in bar)
        // The tick compares with what this drag last showed, not the volume's state, which comes back a frame late.
        assertTrue("val before = if (shown.isNaN()) progressProvider() else shown" in bar)
        assertTrue("if (VolumeSnap.lands(before, now)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)" in bar)
        assertTrue("shown = now\n                    onProgressChange(now)" in bar)
        // It is the player menu's volume bar.
        assertTrue("BigSeekBar(" in source("ui/menu/PlayerMenu.kt"))
    }
}
