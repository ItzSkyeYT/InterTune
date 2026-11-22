/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the Recap can be opened again after its pop-up: Settings and, in its season, Home
 * (RecapCalendar), and the Stats page, asked for in #general on 22 Nov 2026.
 */
class RecapWaysInTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the Stats page opens the Recap for as long as Settings does`() {
        val stats = File(sources, "ui/screens/StatsScreen.kt").readText()
        assertTrue("val recapYear = rememberRecapOnShow()" in stats)
        assertTrue("RecapBanner(year, Modifier.animateItem().padding(horizontal = 12.dp, vertical = 8.dp))" in stats)
        // First on the page, above the periods' chips.
        assertTrue(stats.indexOf("item(key = \"recap_banner\")") in 0 until stats.indexOf("ChipsRow("))
    }
}
