/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which changes of song AutoMix may mix at all, each refusal with its reason. */
class AutomixGuardsTest {

    private val a = AutomixGuards.Song("a", "album-a", 200_000)
    private val b = AutomixGuards.Song("b", "album-b", 180_000)

    private fun snapshot(
        settingOn: Boolean = true,
        stageInChain: Boolean = true,
        casting: Boolean = false,
        repeatOne: Boolean = false,
        current: AutomixGuards.Song? = a,
        next: AutomixGuards.Song? = b,
        speed: Float = 1f,
        pitch: Float = 1f,
        skipSilence: Boolean = false,
        headTracking: Boolean = false,
        upmix: Boolean = false,
        ambience: Boolean = false,
        ffmpeg: Boolean = false,
        sleepTimer: Boolean = false,
        seekedLate: Boolean = false,
    ) = AutomixGuards.Snapshot(
        settingOn, stageInChain, casting, repeatOne, current, next, speed, pitch, skipSilence,
        headTracking, upmix, ambience, ffmpeg, sleepTimer, seekedLate,
    )

    @Test
    fun `two songs of different albums, a minute or longer, with nothing in the way, may be mixed`() {
        assertNull(AutomixGuards.whyNot(snapshot()))
    }

    @Test
    fun `each guard refuses on its own, and says why`() {
        val refusals = mapOf(
            "AutoMix is off" to snapshot(settingOn = false),
            "casting" to snapshot(casting = true),
            "nothing is playing" to snapshot(current = null),
            "repeat one is on" to snapshot(repeatOne = true),
            "nothing comes next" to snapshot(next = null),
            "the same song comes again" to snapshot(next = AutomixGuards.Song("a", "album-a", 200_000)),
            "the two songs are from one album" to snapshot(next = AutomixGuards.Song("b", "album-a", 180_000)),
            "this song is under a minute" to snapshot(current = AutomixGuards.Song("a", null, 59_999)),
            "this song's length is not known" to snapshot(current = AutomixGuards.Song("a", null, -1)),
            "the next song is under a minute" to snapshot(next = AutomixGuards.Song("b", null, 54_000)),
            "the next song's length is not known" to snapshot(next = AutomixGuards.Song("b", null, -1)),
            "the speed or the pitch is not as recorded" to snapshot(speed = 1.25f),
            "the speed or the pitch is not as recorded" to snapshot(pitch = 0.9f),
            "skip silence is on" to snapshot(skipSilence = true),
            "head tracking is on" to snapshot(headTracking = true),
            "the surround upmix is on" to snapshot(upmix = true),
            "the spatial Ambience is on" to snapshot(ambience = true),
            "FFmpeg decodes this build's audio" to snapshot(ffmpeg = true),
            "a sleep timer is set" to snapshot(sleepTimer = true),
            "the song was moved within its last minute" to snapshot(seekedLate = true),
            "AutoMix was not on yet as this song started" to snapshot(stageInChain = false),
        )
        for ((why, s) in refusals) assertEquals(why, AutomixGuards.whyNot(s))
    }

    @Test
    fun `a minute exactly is long enough`() {
        assertNull(AutomixGuards.whyNot(snapshot(current = AutomixGuards.Song("a", null, 60_000), next = AutomixGuards.Song("b", null, 60_000))))
    }

    @Test
    fun `songs with no album, or a blank one, are never taken for one album`() {
        assertFalse(AutomixGuards.sameAlbum(AutomixGuards.Song("a", null, 1), AutomixGuards.Song("b", null, 1)))
        assertFalse(AutomixGuards.sameAlbum(AutomixGuards.Song("a", " ", 1), AutomixGuards.Song("b", " ", 1)))
        assertTrue(AutomixGuards.sameAlbum(AutomixGuards.Song("a", "x", 1), AutomixGuards.Song("b", "x", 1)))
    }
}
