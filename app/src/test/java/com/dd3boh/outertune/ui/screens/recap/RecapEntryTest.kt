/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Recap's pop-up comes once a year, and on its day: not again after the Recap was opened from
 * its notification or a banner, and to an app left open since the evening before as well.
 */
class RecapEntryTest {
    private val entry = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapEntry.kt").readText()

    @Test
    fun `a Recap opened from its notification or a banner is written down as that year's pop-up`() {
        val door = entry.substringAfter("LaunchedEffect(requested) {").substringBefore("\n    }\n")
        assertTrue("countAsPopUp(context, year)" in door)
        val count = entry.substringAfter("private suspend fun countAsPopUp(").substringBefore("\n}\n")
        // Read and written in one go, so the switch just changed (the early reveal, the debug sample) is the one that counts.
        assertTrue("RecapCalendar.popUpShownBy(LocalDate.now(), year, revealedEarly && BuildConfig.PREVIEW_FEATURES)" in count)
        assertTrue("if ((prefs[RecapPopUpShownKey] ?: 0) < shown) prefs[RecapPopUpShownKey] = shown" in count)
    }

    @Test
    fun `the day is read again when the app comes back and when the date changes, not once for the life of the screen`() {
        assertFalse("remember { LocalDate.now() }" in entry)
        val today = entry.substringAfter("internal fun rememberToday(): LocalDate {").substringBefore("\n}\n")
        assertTrue("Lifecycle.Event.ON_START" in today)
        for (action in listOf("Intent.ACTION_DATE_CHANGED", "Intent.ACTION_TIME_CHANGED", "Intent.ACTION_TIMEZONE_CHANGED")) {
            assertTrue(action, action in today)
        }
        assertTrue("context.unregisterReceiver(moved)" in today && "lifecycle.removeObserver(back)" in today)
        // The pop-up looks again when the day moves on, and goes by the date as it looks.
        val popUp = entry.substringAfter("fun RecapEntry(").substringBefore("\n}\n")
        assertTrue("LaunchedEffect(setupDone, quiet, shownYear, early, today, onScreen) {" in popUp)
        assertTrue("RecapCalendar.popUpOwed(now, shownYear, early)" in popUp)
        // So do the banners on Home, in Settings and on the Stats page.
        for (banner in listOf("fun rememberRecapOnShow(): Int? {", "fun rememberRecapOnHome(): Int? {")) {
            assertTrue(banner, "val today = rememberToday()" in entry.substringAfter(banner).substringBefore("\n}\n"))
        }
    }

    @Test
    fun `the pop-up is decided and written down as shown only with the app on screen, so its notification still comes`() {
        // 28 Nov 2026, the Recap's notification on the release candidate: a screen in the background is
        // composed again all the same (on ON_STOP Compose 1.10 only holds back withFrameNanos, see
        // OpensOnScreen), so the date changing at midnight, or the clock set, decided the pop-up there and
        // wrote its year down as shown before anyone saw it. The notification's look then found it shown and
        // told nothing on the reveal day, on the very phone it is for, where the app is not opened.
        val popUp = entry.substringAfter("fun RecapEntry(").substringBefore("\n}\n")
        // The same rule as its window's: started, and not on an activity that is closing.
        assertTrue("val onScreen = windowMayOpen(" in popUp)
        val owed = popUp.substringAfter("LaunchedEffect(setupDone, quiet, shownYear, early, today, onScreen) {").substringBefore("\n    }\n")
        assertTrue(owed.indexOf("!onScreen") in 0 until owed.indexOf("setShownYear(year)"))
        assertTrue(owed.indexOf("!onScreen") in 0 until owed.indexOf("RecapReader.hasEnough(database, year)"))
    }
}
