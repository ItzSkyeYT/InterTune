/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sleep timer's sheet, which is the same one whether a timer runs or not: what it offers when
 * setting one and when showing one, that its switches are the settings themselves, and that every
 * button for the timer opens it. No sheet runs on the JVM, so these read the source, as the other
 * wiring tests do. The texts are SleepTimerWiringTest's.
 */
class SleepTimerSheetTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun fileCode(path: String) = code(File(sources, path).readText())

    private val sheet = fileCode("ui/component/SleepTimer.kt")
    private val player = fileCode("ui/player/Player.kt")
    private val playerMenu = fileCode("ui/menu/PlayerMenu.kt")

    @Test
    fun `every button for the sleep timer opens the sheet, running or not`() {
        assertFalse("sleepTimer.clear()" in player)
        assertFalse("sleepTimer.clear()" in playerMenu)
        assertEquals(4, Regex("""showSleepTimerDialog = true""").findAll(player + "\n" + playerMenu).count())
        assertEquals(1, Regex("""showSleepTimerDialog = true""").findAll(playerMenu).count())
    }

    @Test
    fun `the switches of the sheet are the settings themselves`() {
        for (key in listOf("SleepTimerFadeKey", "SleepTimerFadeDurationKey", "SleepScheduleKey", "SleepScheduleStartKey", "SleepTimerSongFinishKey")) {
            assertTrue("the sheet does not read $key", "rememberPreference($key," in sheet)
        }
        // The time of the night is the one of Player and audio, picked on the same dialog and kept to the same range.
        assertTrue("TimeOfDayDialog(" in sheet)
        assertTrue("ScheduledSleep.startOf(storedStart)" in sheet)
        assertTrue("R.string.sleep_schedule_start" in sheet)
        val settings = fileCode("ui/screens/settings/fragments/PlayerFrag.kt")
        assertTrue("rememberPreference(SleepScheduleKey," in settings)
        assertTrue("rememberPreference(SleepScheduleStartKey," in settings)
        assertTrue("key = SleepTimerFadeKey," in settings)
    }

    @Test
    fun `setting one has the three rows and its two buttons, a running one the chips and its two buttons`() {
        for (row in listOf("sleep_row_in", "sleep_row_at", "sleep_row_end_of", "sleep_end_song", "sleep_end_album")) {
            assertTrue("$row is not in the sheet", "R.string.$row" in sheet)
        }
        assertTrue("SleepTimerDefaults.IN_MINUTES" in sheet)
        assertTrue("onEnd(SleepEnd.SONG)" in sheet && "onEnd(SleepEnd.ALBUM)" in sheet && "onEnd(SleepEnd.QUEUE)" in sheet)
        assertTrue("android.R.string.cancel" in sheet && "R.string.sleep_start" in sheet)
        assertTrue("R.string.sleep_stop" in sheet && "R.string.sleep_close" in sheet)
        assertTrue("timer.start(sleepTimerValue.roundToInt())" in sheet)
        assertTrue("timer.clear()" in sheet)
        // Running: +5, +15 and +30 move the stop, and the end of the song is a chip too.
        assertTrue("SleepTimerDefaults.EXTEND_MINUTES" in sheet && "timer.extend(minutes)" in sheet)
        assertEquals(listOf(5, 15, 30), com.dd3boh.outertune.constants.SleepTimerDefaults.EXTEND_MINUTES)
        assertEquals(listOf(10, 15, 30, 45, 60, 90), com.dd3boh.outertune.constants.SleepTimerDefaults.IN_MINUTES)
        assertTrue("R.string.sleep_stops_at_after_song" in sheet && "R.string.sleep_stops_at" in sheet)
    }

    @Test
    fun `the lengths sit three to a row, and a switch that does not apply dims its words and not its switch`() {
        assertTrue("ChipRow(stringResource(R.string.sleep_row_in), perRow = 3)" in sheet)
        assertTrue("maxItemsInEachRow = perRow" in sheet)
        val row = sheet.substringAfter("private fun SwitchRow(").substringBefore("/** A labelled row of chips")
        // The words carry the alpha, once; the row and the switch do not, which left the switch invisible.
        assertEquals(1, Regex("""\.alpha\(""").findAll(row).count())
        assertTrue(".alpha(if (enabled) 1f else 0.5f)," in row.substringAfter("modifier = Modifier\n                .weight(1f)"))
    }

    @Test
    fun `what does not fit scrolls above the buttons, which stay where they are`() {
        val body = sheet.substringAfter("fun SleepTimerDialog(")
        val scroll = body.indexOf(".weight(1f, fill = false)")
        val scrolling = body.indexOf(".verticalScroll(rememberScrollState())")
        val buttons = body.indexOf("R.string.sleep_start")
        assertTrue(scroll in 0 until scrolling)
        // The words, the rows and the switches are inside the scroll, and Start after it.
        assertTrue(body.indexOf("SleepSwitches(letFinishApplies") in scrolling until buttons)
        // Not the whole sheet: a scroll round the buttons put Start at the end of one on a phone's side.
        assertEquals(1, Regex("""\.verticalScroll\(""").findAll(body.substringBefore("private fun SetTimer(")).count())
    }

    @Test
    fun `the menu entry names the time left only where it can be told`() {
        val grid = fileCode("ui/menu/GridMenu.kt")
        assertTrue("if (enabled && sleepTimerTimeLeft > 0) makeTimeString(sleepTimerTimeLeft)" in grid)
    }

    @Test
    fun `the schedule's lengths are the ones they were, whatever the sheet offers`() {
        assertEquals(listOf(15, 30, 45, 60, 75, 90), com.dd3boh.outertune.constants.SleepTimerDefaults.PRESET_MINUTES)
    }
}
