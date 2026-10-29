/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.media.AudioDeviceInfo
import com.dd3boh.outertune.utils.ProblemLog
import com.dd3boh.outertune.utils.SoundRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * This run's playback in one line for Report a problem: see PlayHealth. A report of 26 Oct 2026
 * showed streams cut as slower than their songs, and nothing in it said how the music had gone
 * around them.
 */
class PlayHealthTest {
    private val second = 1_000_000_000L

    @Test
    fun `a run with nothing played says so`() {
        assertEquals("no song started yet", PlayHealth().describe(0))
    }

    @Test
    fun `waits are counted and timed, the longest kept, and one still going is told`() {
        val h = PlayHealth()
        h.song("Bluetooth")
        h.waiting(true, 1_000)
        h.waiting(true, 1_500) // the same wait, heard again
        h.waiting(false, 1_600)
        h.song()
        h.waiting(true, 10_000)
        h.waiting(false, 17_200)
        h.waiting(true, 20_000)
        assertEquals(
            "2 songs; waited for data 3 times, 9.8 s in all, the longest 7.2 s, waiting now for 2.0 s; sound to Bluetooth",
            h.describe(22_000),
        )
    }

    @Test
    fun `the slowest stream is the one furthest behind its own song, not the lowest number`() {
        val h = PlayHealth()
        h.song()
        // 40 KB/s for a song that needs 16: well ahead.
        h.paced(80_000, 2 * second, 16_000.0)
        // 6 KB/s for a song that needs 5: just ahead, though slower than the next.
        h.paced(12_000, 2 * second, 5_000.0)
        // 8 KB/s for a song that needs 16: half its pace, the slowest.
        h.paced(16_000, 2 * second, 16_000.0)
        h.slowCut()
        h.silenceCut()
        h.silenceCut()
        h.failed()
        assertEquals(
            "1 song; streams came in at 18.0 KB/s while the player waited on them, the slowest at 8.0 KB/s where its song needs 16.0 KB/s; " +
                "1 slow connection cut; 2 silent connections cut; 1 song failed",
            h.describe(0),
        )
    }

    @Test
    fun `rates and durations read at a glance`() {
        assertEquals("5.8 KB/s", PlayHealth.rate(5_800.0))
        assertEquals("310 KB/s", PlayHealth.rate(310_400.0))
        assertEquals("2.3 MB/s", PlayHealth.rate(2_300_000.0))
        assertEquals("0.6 s", PlayHealth.duration(600))
        assertEquals("14 s", PlayHealth.duration(14_400))
        assertEquals("2 min 5 s", PlayHealth.duration(125_000))
    }

    @Test
    fun `the meter tells each window it judges, what came in and how long it was waited for`() {
        val seen = mutableListOf<Pair<Long, Long>>()
        val meter = StreamPace.Meter(20_000.0, judged = { bytes, nanos -> seen += bytes to nanos })
        meter.slow(30_000, second)
        assertTrue("judged before its window was full", seen.isEmpty())
        meter.slow(20_000, second)
        assertEquals(listOf(50_000L to 2 * second), seen)
    }

    @Test
    fun `the sound goes by kind, never by name`() {
        assertEquals("Bluetooth", SoundRoute.kind(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("the phone's speaker", SoundRoute.kind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("wired headphones", SoundRoute.kind(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals("another output", SoundRoute.kind(-1))
        // Before Android 13, headphones plugged in win over the speaker that is always there.
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            SoundRoute.likeliest(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, SoundRoute.likeliest(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        val source = File("src/main/java/com/dd3boh/outertune/utils/SoundRoute.kt").readText()
        assertTrue("a device's name went into the report", "productName" !in source && "getProductName" !in source && ".address" !in source)
    }

    @Test
    fun `this run's playback comes under the phone in the report`() {
        val report = ProblemLog.report(
            ProblemLog.Excerpt(emptyList(), null), build = "0.12.5", device = "Pixel 5, Android 14",
            installedFrom = "GitHub", phoneNow = "Wi-Fi", playback = "3 songs",
        )
        val lines = report.lines()
        assertEquals("Phone now: Wi-Fi", lines[3])
        assertEquals("Playback this run: 3 songs", lines[4])
    }

    @Test
    fun `the service counts the player's own streams only, and logs the line at every song`() {
        val body = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(body.contains("health = if (calls === playerCalls) PlayHealth.thisRun else null"))
        assertTrue(Regex("""PlayHealth\.thisRun\.song\([^\n]*\n\s*Log\.i\(TAG, "health: """).containsMatchIn(body))
    }
}
