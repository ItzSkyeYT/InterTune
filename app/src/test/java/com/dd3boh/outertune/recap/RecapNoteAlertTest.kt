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

/** "A note from your past self is waiting": once a year, when that year's Recap is out with notes sealed for it, and never without. */
class RecapNoteAlertTest {
    @Test
    fun `told once a year, when that year's Recap is out and notes were sealed for it`() {
        val counts = mapOf(2025 to 2, 2026 to 1)
        assertEquals(2026 to 2, RecapNoteAlert.due(upTo = 2026, toldYear = 0, counts = counts))
        // Told: not again that year. The next year's Recap tells about its own.
        assertNull(RecapNoteAlert.due(upTo = 2026, toldYear = 2026, counts = counts))
        assertEquals(2027 to 1, RecapNoteAlert.due(upTo = 2027, toldYear = 2026, counts = counts))
    }

    @Test
    fun `no notes, notes for a Recap not out yet, or no Recap out: no notification`() {
        assertNull(RecapNoteAlert.due(upTo = 2026, toldYear = 0, counts = emptyMap()))
        assertNull(RecapNoteAlert.due(upTo = 2026, toldYear = 0, counts = mapOf(2025 to 0)))
        assertNull(RecapNoteAlert.due(upTo = 2026, toldYear = 0, counts = mapOf(2026 to 3)))
        assertNull(RecapNoteAlert.due(upTo = null, toldYear = 0, counts = mapOf(2025 to 1)))
    }

    @Test
    fun `it comes heads-up on a channel of its own, named in the app's language, and is remembered only once posted`() {
        val code = File("src/main/java/com/dd3boh/outertune/recap/RecapNoteAlert.kt").readText()
        assertTrue("NotificationManager.IMPORTANCE_HIGH" in code && "NotificationCompat.PRIORITY_MAX" in code)
        // Without the permission to post (Android 13 and up), nothing, and it is looked for again next time.
        assertTrue(code.indexOf("if (!notifier.areNotificationsEnabled()) return false") in 0 until code.indexOf("notifier.notify("))
        assertTrue("if (post(context, recap, count)) context.dataStore.edit { it[RecapNotesToldKey] = recap }" in code)
        assertEquals(com.dd3boh.outertune.R.string.recap_notes_channel, ChannelNames.NAMES[RecapNoteAlert.CHANNEL_ID])
        // Looked for as the app starts, and on the reveal day's morning by a worker, whether or not the app is opened.
        val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
        assertTrue("runCatching { RecapNoteAlert.check(this@App) }" in app && "RecapNoteAlert.schedule(this@App)" in app)
        assertTrue("RecapCalendar.revealDay(now.year).atTime(MORNING, 0)" in code)
        // A tap opens that year's Recap.
        assertTrue("RecapDoor.open(year)" in File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText())
    }
}
