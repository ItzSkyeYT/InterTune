/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.utils.ChannelNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Your 2026 Recap is here": once a year, when it is out with enough music, its notes named only when there are some. */
class RecapAlertTest {
    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `told once a year, when that year's Recap is out, and the notes waiting in it counted`() {
        val counts = mapOf(2025 to 3, 2026 to 1)
        assertEquals(RecapAlert.Due(2026, 3), RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 0, counts = counts))
        // Told, or opened here already: not again that year. The next year's is told about in its turn.
        assertNull(RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 2026, counts = counts))
        assertEquals(RecapAlert.Due(2027, 1), RecapAlert.due(onShow = 2027, inSeason = true, enough = true, toldYear = 2026, counts = counts))
        // No notes: the Recap all the same, with no notes to name.
        assertEquals(RecapAlert.Due(2026, 0), RecapAlert.due(onShow = 2026, inSeason = true, enough = true, toldYear = 0, counts = emptyMap()))
    }

    @Test
    fun `nothing before the Recap is out, after its season, or for a year with too little music`() {
        assertNull(RecapAlert.due(onShow = null, inSeason = false, enough = true, toldYear = 0, counts = mapOf(2025 to 1)))
        assertNull(RecapAlert.due(onShow = 2026, inSeason = false, enough = true, toldYear = 0, counts = mapOf(2025 to 1)))
        assertNull(RecapAlert.due(onShow = 2026, inSeason = true, enough = false, toldYear = 0, counts = mapOf(2025 to 1)))
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
        // Out as the pop-up has it: the calendar with the early reveal, its season, enough music.
        assertTrue("RecapCalendar.yearOnShow(today, early)" in alert && "RecapCalendar.onHome(today, early)" in alert && "RecapReader.hasEnough(database, onShow)" in alert)
        assertEquals(com.dd3boh.outertune.R.string.recap_name, ChannelNames.NAMES[RecapAlert.CHANNEL_ID])
        // Looked for as the app starts and on the reveal day's morning; a Recap opened here counts as told; a tap opens it.
        val app = code("App.kt")
        assertTrue("runCatching { RecapAlert.check(this@App) }" in app && "RecapAlert.schedule(this@App)" in app)
        assertTrue("RecapCalendar.revealDay(now.year).atTime(MORNING, 0)" in alert)
        assertTrue("if ((it[RecapToldKey] ?: 0) < year) it[RecapToldKey] = year" in code("recap/RecapNotesOpen.kt"))
        assertTrue("RecapDoor.open(year)" in code("MainActivity.kt"))
    }
}
