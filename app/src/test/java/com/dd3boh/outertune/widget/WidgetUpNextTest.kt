/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A change of the queue's next songs alone redraws no widget unless one of them draws Up next, and a
 * phone's widget has a layout for its side as well as upright, so "wide enough" is about every size
 * a widget is composed at. Where the plan draws Up next the store must say so, at every size.
 */
class WidgetUpNextTest {
    private val styles = listOf(
        WidgetSettings(style = WidgetStyle.COVER),
        WidgetSettings(style = WidgetStyle.FULL_COVER),
        WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.BIG_COVER),
        WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.CRATE),
        WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.RECORDS),
        WidgetSettings(style = WidgetStyle.SHELF, content = WidgetContent.LIST),
    )

    @Test
    fun `wherever the plan draws Up next the store says a widget does`() {
        var panes = 0
        for (settings in styles) for (w in 40..760 step 10) for (h in 40..760 step 10) for (font in listOf(1f, 1.3f)) {
            val plan = WidgetLayout.plan(w, h, settings, available = 6, fontScale = font, upNext = WidgetLayout.MAX_UP_NEXT)
            if (plan.shape == WidgetShape.PANES) {
                panes++
                assertTrue("$settings at ${w}x$h draws Up next", drawsUpNext(settings, w, h))
            }
        }
        assertTrue("the sweep reached a tablet's panes", panes > 0)
    }

    @Test
    fun `the Pixel 5's widget, upright and on its side, draws no Up next, and a tablet's does`() {
        val full = WidgetSettings(style = WidgetStyle.FULL_COVER)
        assertFalse(drawsUpNext(full, 277, 220))
        assertFalse(drawsUpNext(full, 530, 117))
        assertTrue(drawsUpNext(full, 530, 220))
        assertTrue(drawsUpNext(WidgetSettings(style = WidgetStyle.COVER), 700, 300))
        assertFalse("a Shelf has no panes", drawsUpNext(WidgetSettings(style = WidgetStyle.SHELF), 700, 300))
        assertEquals(500, WidgetLayout.PANES_WIDTH_DP)
    }
}
