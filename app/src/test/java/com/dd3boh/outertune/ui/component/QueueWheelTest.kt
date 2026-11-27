/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.ui.player.QueueWheel
import com.dd3boh.outertune.ui.player.QueueWheel.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue's wheel (Ti-Die, #testers 26 Nov 2026): a light tick as each song row crosses the middle of the list under
 * the finger or a fling; never when the app moves the list, never faster than every 40 ms, never twice for a row.
 */
class QueueWheelTest {
    private val rows = (0..9).map { Row(it, it * 100 - 50, 100) }

    @Test
    fun `the middle row is the one across the middle of the viewport`() {
        assertEquals(5, QueueWheel.middleRow(rows, 0, 1000).let { it })
        assertEquals(null, QueueWheel.middleRow(emptyList(), 0, 1000))
        assertEquals(2, QueueWheel.middleRow(rows, 0, 400))
    }

    @Test
    fun `a row crossing under the finger ticks once, and the first look never does`() {
        val w = QueueWheel()
        w.byFinger(0)
        assertFalse(w.crossed(3, 0))
        assertFalse("the same row again", w.crossed(3, 10))
        w.byFinger(100)
        assertTrue(w.crossed(4, 100))
        w.byFinger(200)
        assertTrue(w.crossed(3, 200))
    }

    @Test
    fun `nothing faster than every 40 ms, and a skipped row is not caught up later`() {
        val w = QueueWheel()
        w.byFinger(0)
        w.crossed(0, 0)
        w.byFinger(1000)
        assertTrue(w.crossed(1, 1000))
        w.byFinger(1010)
        assertFalse(w.crossed(2, 1010))
        w.byFinger(1030)
        assertFalse(w.crossed(2, 1030))
        w.byFinger(1050)
        assertTrue(w.crossed(3, 1050))
    }

    @Test
    fun `the app scrolling the list itself ticks nothing, nor does the row it lands on tick later`() {
        val w = QueueWheel()
        w.crossed(0, 0)
        // A jump to the playing song, long after any finger: no tick.
        assertFalse(w.crossed(40, 5000))
        // The finger then moves it one row on: that is a tick.
        w.byFinger(5100)
        assertTrue(w.crossed(41, 5100))
        // A move after a drag, the finger long gone.
        assertFalse(w.crossed(12, 9000))
    }

    @Test
    fun `a fling that goes on reports every frame, so it keeps the finger's say`() {
        val w = QueueWheel()
        w.byFinger(0)
        w.crossed(0, 0)
        var ticks = 0
        for (t in 16..1000 step 16) {
            w.byFinger(t.toLong())
            if (w.crossed(t / 16, t.toLong())) ticks++
        }
        assertTrue("$ticks", ticks in 20..25)
        // Held row's drag: its fling is the finger's too, while it runs.
        val h = QueueWheel()
        h.crossed(0, 0)
        h.holding = true
        assertTrue(h.crossed(1, 10_000))
        h.holding = false
        assertFalse(h.crossed(2, 20_000))
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `the queue's songs list ticks through the setting, only from the finger's own scrolls`() {
        val queue = source("ui/player/Queue.kt")
        assertTrue(".nestedScroll(wheel.connection)" in queue)
        assertTrue("wheel.crossed(middle, SystemClock.uptimeMillis())" in queue)
        assertTrue("wheelHaptic.wheelTick(wheelView, wheelLive)" in queue)
        assertTrue(".movesListFromHeldRow(lazySongsListState, wheel)" in queue)
        // The tick goes through the setting: nothing under Off, the step's strength, Light's own under Light.
        val tick = source("ui/component/MoreHaptics.kt").substringAfter("fun HapticFeedback.wheelTick(").substringBefore("\n}")
        assertTrue(tick, "segmentTick(view, live, frequent = true)" in tick)
        val seg = source("ui/component/MoreHaptics.kt").substringAfter("fun HapticFeedback.segmentTick(").substringBefore("\n}")
        assertTrue("Haptics.play(strong)" in seg)
        // The wheel reads only the finger's: the app's own scrolls (scrollToItem and the like) never call byFinger.
        val wheel = source("ui/player/QueueWheel.kt")
        assertEquals(1, Regex("""byFinger\(android""").findAll(wheel).count())
        assertFalse("scrollToItem" in queue.substringAfter("val wheel = remember").substringBefore("LazyColumn("))
    }
}
