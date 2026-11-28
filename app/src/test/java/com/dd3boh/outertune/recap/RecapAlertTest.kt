/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import androidx.work.ExistingWorkPolicy
import com.dd3boh.outertune.utils.ChannelNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** "Your 2026 Recap is here": once a year, when it is out with enough music, its notes named only when there are some. */
class RecapAlertTest {
    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0, year: Int = 2026) = LocalDateTime.of(year, month, day, hour, minute)

    @Test
    fun `told once a year, when that year's Recap is out, and the notes waiting in it counted`() {
        val counts = mapOf(2025 to 3, 2026 to 1)
        assertEquals(RecapAlert.Due(2026, 3), RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 0, popUpShown = 0, counts = counts))
        // Told, or opened here already: not again that year. The next year's is told about in its turn.
        assertNull(RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 2026, popUpShown = 0, counts = counts))
        assertEquals(RecapAlert.Due(2027, 1), RecapAlert.due(onShow = 2027, inSeason = true, enough = true, toldYear = 2026, popUpShown = 2026, counts = counts))
        // No notes: the Recap all the same, with no notes to name.
        assertEquals(RecapAlert.Due(2026, 0), RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 0, popUpShown = 0, counts = emptyMap()))
    }

    @Test
    fun `nothing before the Recap is out, after its season, or for a year with too little music`() {
        assertNull(RecapAlert.due(onShow = null, inSeason = false, enough = true, toldYear = 0, popUpShown = 0, counts = mapOf(2025 to 1)))
        assertNull(RecapAlert.due(onShow = 2026, inSeason = false, enough = true, toldYear = 0, popUpShown = 0, counts = mapOf(2025 to 1)))
        assertNull(RecapAlert.due(onShow = 2026, inSeason = true, enough = false, toldYear = 0, popUpShown = 0, counts = mapOf(2025 to 1)))
    }

    @Test
    fun `nothing once the pop-up has shown that year's Recap`() {
        // The app opened first: its pop-up told, and a notification beside it would say it twice.
        assertNull(RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 0, popUpShown = 2026, counts = emptyMap()))
        // Last year's pop-up says nothing about this year's.
        assertEquals(RecapAlert.Due(2027, 0), RecapAlert.due(onShow = 2027, inSeason = true, enough = true, toldYear = 2026, popUpShown = 2026, counts = emptyMap()))
    }

    @Test
    fun `it comes at a decent hour, never in the night`() {
        assertFalse(RecapAlert.daytime(at(12, 2, 3)))
        assertFalse(RecapAlert.daytime(at(12, 2, 9, 59)))
        assertTrue(RecapAlert.daytime(at(12, 2, 10)))
        assertTrue(RecapAlert.daytime(at(12, 2, 20, 59)))
        assertFalse(RecapAlert.daytime(at(12, 2, 21)))
        assertFalse(RecapAlert.daytime(at(12, 2, 23, 30)))
        // A moment in the day stays; one in the night waits for the morning, that day's or the next.
        assertEquals(at(12, 5, 14, 30), RecapAlert.nextDaytime(at(12, 5, 14, 30)))
        assertEquals(at(12, 2, 10), RecapAlert.nextDaytime(at(12, 2, 3)))
        assertEquals(at(12, 3, 10), RecapAlert.nextDaytime(at(12, 2, 21, 15)))
        assertEquals(at(1, 1, 10, year = 2027), RecapAlert.nextDaytime(at(12, 31, 23, 50)))
    }

    @Test
    fun `a start of the app looks a while later, so that a pop-up opening with it goes first, and never in the night`() {
        // Opened after the update on 5 December: a look half an hour later, when the welcome page and the pop-up have had their turn.
        assertEquals(at(12, 5, 14, 30), RecapAlert.lookAfterStart(at(12, 5, 14, 0), owed = true))
        // The phone started at 3 in the morning on the reveal day (a system update): the look is at 10, not at 3.
        assertEquals(at(12, 2, 10), RecapAlert.lookAfterStart(at(12, 2, 3, 0), owed = true))
        // Late in the evening: the next morning.
        assertEquals(at(12, 3, 10), RecapAlert.lookAfterStart(at(12, 2, 20, 45), owed = true))
        // Nothing owed (before the reveal day, told already, its pop-up shown): the next reveal day's morning.
        assertEquals(at(12, 2, 10), RecapAlert.lookAfterStart(at(11, 26, 9, 30), owed = false))
        assertEquals(at(12, 2, 10, year = 2027), RecapAlert.lookAfterStart(at(12, 2, 12, 0), owed = false))
    }

    @Test
    fun `a start never puts off a look already planned, the start the look itself makes included`() {
        val wanted = at(12, 2, 10, 30)
        // Planned as soon or sooner: kept, so the look runs (a look that starts the app would otherwise move itself on for good).
        assertEquals(ExistingWorkPolicy.KEEP, RecapAlert.policyAtStart(planned = at(12, 2, 10), wanted = wanted))
        assertEquals(ExistingWorkPolicy.KEEP, RecapAlert.policyAtStart(planned = wanted, wanted = wanted))
        // Planned later (next year's, after a look that found nothing to tell then), or never: planned anew.
        assertEquals(ExistingWorkPolicy.REPLACE, RecapAlert.policyAtStart(planned = at(12, 2, 10, year = 2027), wanted = wanted))
        assertEquals(ExistingWorkPolicy.REPLACE, RecapAlert.policyAtStart(planned = null, wanted = wanted))
        // The time a look is planned for is written on it, and read back.
        assertEquals(at(12, 5, 14, 30), RecapAlert.plannedAt(setOf("com.dd3boh.outertune.recap.RecapAlertWorker", RecapAlert.tagFor(at(12, 5, 14, 30)))))
        assertNull(RecapAlert.plannedAt(setOf("com.dd3boh.outertune.recap.RecapAlertWorker")))
        assertNull(RecapAlert.plannedAt(null))
    }

    @Test
    fun `it comes heads-up on the Recap's channel, names the notes only when some wait, and is remembered only once posted`() {
        val alert = code("recap/RecapAlert.kt")
        assertTrue("NotificationManager.IMPORTANCE_HIGH" in alert && "NotificationCompat.PRIORITY_MAX" in alert)
        assertTrue("if (notes > 0) context.resources.getQuantityString(R.plurals.recap_alert_notes, notes, notes)" in alert)
        assertTrue("else context.getString(R.string.recap_alert_line)" in alert)
        // Without the permission to post (Android 13 and up), nothing, and it is looked for again next time.
        assertTrue(alert.indexOf("if (!notifier.areNotificationsEnabled()) return false") in 0 until alert.indexOf("notifier.notify("))
        assertTrue("if (post(context, due)) context.dataStore.edit { it[RecapToldKey] = due.recap }" in alert)
        // Out as the pop-up has it: the calendar with the early reveal, its season, enough music; and not once the pop-up has shown it.
        assertTrue("RecapCalendar.yearOnShow(today, early)" in alert && "RecapCalendar.onHome(today, early)" in alert && "RecapReader.hasEnough(database, onShow)" in alert)
        assertTrue("val shown = prefs[RecapPopUpShownKey] ?: 0" in alert)
        assertEquals(com.dd3boh.outertune.R.string.recap_name, ChannelNames.NAMES[RecapAlert.CHANNEL_ID])
        // Looked for on the reveal day's morning, and a while after a start of the app; a Recap opened here counts as told; a tap opens it.
        assertTrue("RecapCalendar.revealDay(now.year).atTime(MORNING, 0)" in alert)
        assertTrue("if (post(context, due))" in alert.substringAfter("suspend fun look(").substringBefore("\n    }\n"))
        assertTrue("if (due != null && !daytime(now))" in alert.substringAfter("suspend fun look(").substringBefore("\n    }\n"))
        assertTrue("RecapAlert.look(applicationContext)" in alert)
        assertTrue("if ((it[RecapToldKey] ?: 0) < year) it[RecapToldKey] = year" in code("recap/RecapNotesOpen.kt"))
        assertTrue("RecapDoor.open(year)" in code("MainActivity.kt"))
    }

    @Test
    fun `a start of the app only plans the look, and tells nothing itself`() {
        // At the start, the pop-up has not had its turn yet: a notification then came at the same moment
        // as the pop-up, or at 3 in the morning when the phone started for a system update.
        val app = code("App.kt")
        assertTrue("runCatching { RecapAlert.plan(this@App) }" in app)
        assertFalse("RecapAlert.check(" in app)
        val plan = code("recap/RecapAlert.kt").substringAfter("suspend fun plan(").substringBefore("\n    }\n")
        assertFalse("post(" in plan || "tell(" in plan)
        assertTrue("policyAtStart(" in plan)
    }

    @Test
    fun `a look that tells nothing says why in the log, and one that fails says how`() {
        // 28 Nov 2026: two looks on the reveal day told nothing and planned next year's, and the release build
        // said not a word about why. Every way out of find without a Recap to tell names its reason, but a
        // build without the Recap at all; a look or a start that throws writes the throwable.
        val alert = code("recap/RecapAlert.kt")
        val find = alert.substringAfter("private suspend fun find(").substringBefore("\n    }\n")
        assertEquals(listOf("if (!Unreleased.RECAP) return null"), find.lines().map { it.trim() }.filter { "return null" in it })
        assertTrue("none(\"notifications are off\")" in find && "none(\"too little music in \$onShow\")" in find)
        val look = alert.substringAfter("suspend fun look(").substringBefore("\n    }\n")
        assertTrue(".onFailure { Log.w(TAG, \"The look at \$now failed\", it) }" in look)
        val plan = alert.substringAfter("suspend fun plan(").substringBefore("\n    }\n")
        assertTrue(".onFailure { Log.w(TAG, \"The start's look at \$now failed\", it) }" in plan)
        assertTrue("runCatching { RecapAlert.look(applicationContext) }.onFailure { Log.w(" in alert)
    }

    @Test
    fun `a Recap opened here takes its notification out of the shade`() {
        // Watched from the pop-up or a banner: "Your 2026 Recap is here" has nothing left to say.
        val opened = code("recap/RecapNotesOpen.kt").substringAfter("suspend fun opened(").substringBefore("\n    }\n")
        assertTrue("RecapAlert.withdraw(context)" in opened)
        assertTrue("NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)" in code("recap/RecapAlert.kt"))
    }
}
