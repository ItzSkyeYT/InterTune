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
        // With the way to the notes written at the end of a Recap beside it (his ask of 22 Nov 2026).
        assertTrue("RecapBanner(year, Modifier.animateItem().padding(horizontal = 12.dp, vertical = 8.dp), onNotes = { notesOpen = true })" in stats)
        // First on the page, above the periods' chips.
        assertTrue(stats.indexOf("item(key = \"recap_banner\")") in 0 until stats.indexOf("ChipsRow("))
    }

    @Test
    fun `once watched as far as its year card, Home's banner goes, and Settings' and the Stats page's stay`() {
        // His ask of 22 Nov 2026.
        val story = File(sources, "ui/screens/recap/RecapScreen.kt").readText()
        assertTrue("if (slide.kind == SlideKind.CARD || slide.kind == SlideKind.THANKS) viewModel.watched(recap.year)" in story)
        val entry = File(sources, "ui/screens/recap/RecapEntry.kt").readText()
        val home = entry.substringAfter("fun rememberRecapOnHome(): Int? {").substringBefore("\n}\n")
        assertTrue("year.takeIf { inSeason && watched < (year ?: 0) }" in home)
        val shown = entry.substringAfter("fun rememberRecapOnShow(): Int? {").substringBefore("\n}\n")
        assertTrue("RecapWatchedKey" !in shown)
        val model = File(sources, "viewmodels/RecapViewModel.kt").readText()
        assertTrue("if ((it[RecapWatchedKey] ?: 0) < year) it[RecapWatchedKey] = year" in model)
        // Settings and the Stats page go by the year on show alone.
        for (page in listOf("ui/screens/settings/SettingsScreen.kt", "ui/screens/StatsScreen.kt")) {
            assertTrue(page, "rememberRecapOnHome" !in File(sources, page).readText())
        }
    }
}
