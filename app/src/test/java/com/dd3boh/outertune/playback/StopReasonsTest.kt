/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import com.dd3boh.outertune.utils.ProblemLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Issue #26: music that stops by itself says why, in the log and in a small file that outlives
 * the process, and Report a problem shows the file's lines last, where the form keeps them.
 */
class StopReasonsTest {

    @Test
    fun `a pause somebody asked for writes nothing, any other says what did it`() {
        assertNull(StopReasons.pausedLine(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, false, true))
        assertEquals(
            "STOPPED: paused by another app took the sound (screen off, in the foreground)",
            StopReasons.pausedLine(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, false, true),
        )
        // Whether it was in the foreground is not said as it ends: Android has taken it out by then.
        assertEquals("STOPPED: the player's service ended while playing (screen off)", StopReasons.endedLine(screenOn = false))
        assertEquals("STOPPED: the player's service ended while playing", StopReasons.endedLine(null))
    }

    @Test
    fun `the file keeps the last eight lines, dated, the latest last`() {
        var lines = emptyList<String>()
        for (i in 1..10) lines = StopReasons.added(lines, "stop $i", Date(0))
        assertEquals(StopReasons.KEPT, lines.size)
        assertTrue(lines.first().endsWith(" stop 3"))
        assertTrue(lines.last().endsWith(" stop 10"))
        assertTrue(Regex("""^\d\d-\d\d \d\d:\d\d:\d\d stop 10$""").matches(lines.last()))
    }

    @Test
    fun `the report ends with the stops, and the form's cut keeps them`() {
        val lines = (1..400).map { "10-17 18:0${it % 10}:00.000 I/MusicService( 4242): PLAYING: song id = id$it" }
        val stops = listOf("10-17 18:05:01 STOPPED: the player's service ended while playing (screen off, NOT in the foreground)")
        val report = ProblemLog.report(ProblemLog.excerpt(lines), build = "b", device = "d", stops = stops)
        assertTrue(report.endsWith("Stops nobody asked for, the latest last:\n" + stops.single()))
        assertTrue(ProblemLog.fitted(report, 1500).endsWith(stops.single()))
        // And none when there are none.
        assertTrue("Stops nobody" !in ProblemLog.report(ProblemLog.excerpt(lines), build = "b", device = "d"))
    }

    @Test
    fun `the service writes the lines where it stops, pauses and is refused the foreground`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(service.contains("Log.w(TAG, StopReasons.NOT_IN_FOREGROUND)"))
        assertTrue(service.contains("StopReasons.pausedLine(reason, screenOn(), inForeground())"))
        assertTrue(service.contains("StopReasons.endedLine(screenOn())"))
        assertTrue("the screen going off is read while the service lives", service.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue(service.contains("Log.w(TAG, StopReasons.SCREEN_OFF_OUT_OF_FOREGROUND)"))
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/component/ProblemReportDialog.kt").readText()
        assertTrue(dialog.contains("StopReasons.read(context)"))
    }
}
