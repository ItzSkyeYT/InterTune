/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Issue #34 on History. Its day headings are sticky and its list has no top padding, so a heading that
 * is itself the row at the top stays pinned there while it scrolls up under it: Compose reports it at
 * the top of the list however far it has gone. Taken at its word, the heading looked as tall as what
 * was left of it, the place in the list came out up to half a heading wrong, and a grab there sent the
 * list against the finger. FakeList pins headings by Compose's own rule.
 */
class ScrollbarHistoryTest {

    /**
     * History as HistoryScreen.kt builds it: the sticky search row (1 dp while closed), the chips, then
     * for each day a sticky 48 dp heading over 1 to 25 songs. No top padding in the list: the app bar's
     * room is outside it.
     */
    private fun history(days: Int): FakeList {
        val r = Random(days)
        val sizes = arrayListOf(1f, 56f)
        val headings = arrayListOf(0)
        repeat(days) {
            headings += sizes.size
            sizes += 48f
            repeat(1 + r.nextInt(25)) { sizes += 64f }
        }
        return FakeList(sizes, topPaddingDp = 0f, viewportDp = 851f - 94f, headers = headings)
    }

    private fun dp(px: Double) = "%.1f".format(px / DENSITY)

    @Test
    fun `History grabbed anywhere goes with the finger, a pixel up or a pixel down`() {
        val wrong = ArrayList<String>()
        var grabs = 0
        for (days in listOf(2, 3, 5, 10, 20)) for (px in listOf(1f, -1f)) {
            val list = history(days)
            var against = 0
            var worst = 0.0
            var worstAt = 0
            var at = 0
            while (at <= list.maxScroll) {
                list.pos = at.toDouble()
                val press = Press(list)
                if (press.finger(px)) {
                    grabs++
                    val step = list.pos - at
                    if ((px > 0f && step < -0.5) || (px < 0f && step > 0.5)) {
                        against++
                        if (abs(step) > worst) { worst = abs(step); worstAt = at }
                    }
                }
                at++
            }
            if (against > 0) wrong += "History of $days days, finger ${if (px > 0) "down" else "up"} a pixel: $against grabs sent the list the other way, the worst ${dp(worst)} dp, at $worstAt px"
        }
        assertTrue("$grabs grabs:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
