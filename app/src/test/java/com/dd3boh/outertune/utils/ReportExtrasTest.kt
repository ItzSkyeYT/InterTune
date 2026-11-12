/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.media3.common.Player
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.utils.ReportSettings.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What a report gets beyond the log (ReportExtras): the settings of the problem's area and the steps
 * around the problem, put together the same way for the page, the crash handler and these tests.
 */
class ReportExtrasTest {
    private val log = listOf(
        "11-02 15:29:10.100 D/MusicService( 6711): PLAYING: song id = aaaaaaaaaaa",
        "11-02 15:29:40.811 E/MediaCodecAudioRenderer( 6711): Audio sink error",
        "11-02 15:29:40.811 E/MediaCodecAudioRenderer( 6711):   java.lang.IllegalStateException: Unexpected discontinuity",
        "11-02 15:29:41.000 D/QueueBoard( 6711): Starting database save task",
    )

    /** 15:30:00 today: twenty seconds after the fault above. */
    private val now: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 15); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun trail(): ReportTrail {
        var clock = now
        val t = ReportTrail(clock = { clock })
        t.enabled = true
        fun at(secondsAgo: Int, step: ReportTrail.() -> Unit) { clock = now - secondsAgo * 1000L; t.step() }
        at(130) { screen("settings/player") }
        at(125) { setting("skip silence", "on") }
        at(70) { songStarted(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, true, false, false) }
        at(21) { playbackError("ERROR_CODE_AUDIO_TRACK_WRITE_FAILED") }
        return t
    }

    private val prefs = mutablePreferencesOf(SkipSilenceKey to true, AudioQualityKey to "HIGH", InnerTubeCookieKey to "SAPISID=zzz")

    @Test
    fun `a fault in the player gives the playback settings and the steps around the fault`() {
        val excerpt = ProblemLog.excerpt(log)
        val extras = ReportExtras.forLog(log, excerpt, prefs, trail(), now)
        assertEquals(Area.PLAYBACK, extras.area)
        assertEquals(listOf("Settings (playback), * = not the default:", "* audio quality high", "* skip silence on"), extras.settings.take(3))
        val steps = extras.steps!!.lines()
        assertEquals(ReportTrail.HEADER_FAULT, steps[0])
        // The fault was written at 15:29:40.8, 19 s before the clock: the screen opened at 15:27:50 is 1:51 before it.
        assertEquals("-1:51 opened settings/player", steps[1])
        assertEquals("-0:02 playback error: AUDIO_TRACK_WRITE_FAILED", steps[steps.indexOf("0:00 >>> here") - 1])
    }

    @Test
    fun `the log decides the area when the fault is not the player's, and the area's settings come with it`() {
        val lyrics = listOf(
            "11-02 15:29:10.100 W/LyricsHelper( 6711): KuGou failed: timeout",
            "11-02 15:29:40.000 W/System.err( 6711): java.net.SocketTimeoutException",
        )
        val extras = ReportExtras.forLog(lyrics, ProblemLog.excerpt(lyrics), prefs, trail(), now)
        assertEquals(Area.LYRICS, extras.area)
        assertTrue(extras.settings[0].startsWith("Settings (lyrics)"))
    }

    @Test
    fun `an earlier run has its settings as they are now and no steps`() {
        val extras = ReportExtras.forLog(log, ProblemLog.excerpt(log), prefs, null, now)
        assertTrue(extras.settings[0].startsWith("Settings now (playback)"))
        assertNull(extras.steps)
    }

    @Test
    fun `with no fault the steps are those before the report`() {
        val quiet = listOf("11-02 15:29:10.100 D/MusicService( 6711): PLAYING: song id = aaaaaaaaaaa")
        val extras = ReportExtras.forLog(quiet, ProblemLog.excerpt(quiet), prefs, trail(), now)
        assertEquals(ReportTrail.HEADER_NOW, extras.steps!!.lines()[0])
        assertTrue(extras.steps!!.lines().last() == "0:00 >>> now")
    }

    @Test
    fun `facts are asked once the area is known`() {
        val asked = mutableListOf<Area>()
        val recognition = listOf("11-02 15:29:10.100 W/RecognitionEngine( 6711): no match")
        val extras = ReportExtras.forLog(recognition, ProblemLog.excerpt(recognition), prefs, null, now) { asked += it; listOf("microphone allowed") }
        assertEquals(listOf(Area.RECOGNITION), asked)
        assertEquals("Also: signed in, microphone allowed", extras.settings.last())
    }

    @Test
    fun `a crash gets the steps before it and the settings, and nothing when there is nothing to say`() {
        val crash = ReportExtras.forCrash(prefs, trail(), now)!!
        assertEquals(ReportTrail.HEADER_CRASH, crash.steps!!.lines()[0])
        assertTrue(crash.settings.isNotEmpty())
        // No settings read yet and no steps: nothing is added.
        assertNull(ReportExtras.forCrash(null, ReportTrail().also { it.enabled = true }, now))
        // Steps and no settings: the steps alone.
        assertTrue(ReportExtras.forCrash(null, trail(), now)!!.settings.isEmpty())
    }

    @Test
    fun `a crash's block is the settings, an empty line, then the steps`() {
        val both = ReportExtras.block(ReportExtras.forCrash(prefs, trail(), now)!!)
        val settings = both.substringBefore("\n\n")
        assertTrue(settings.startsWith("Settings (playback)") && ReportTrail.HEADER_START !in settings)
        assertTrue(both.substringAfter("\n\n").startsWith(ReportTrail.HEADER_CRASH))
        // Steps and no settings: the steps alone, with no empty line before them.
        assertTrue(ReportExtras.block(ReportExtras.forCrash(null, trail(), now)!!).startsWith(ReportTrail.HEADER_CRASH))
    }

    @Test
    fun `the cookie is never in what is added`() {
        val extras = ReportExtras.forLog(log, ProblemLog.excerpt(log), prefs, trail(), now)
        val all = extras.settings.joinToString("\n") + "\n" + extras.steps
        assertFalse("zzz" in all)
        assertFalse("SAPISID" in all)
    }
}
