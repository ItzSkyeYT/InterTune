/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SpatialAudioMode
import com.dd3boh.outertune.ui.dialog.SignalPath
import com.dd3boh.outertune.ui.dialog.SignalPath.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Signal path sheet: what changes the sound is amber, what does not is green, and its place in the menu. */
class SignalPathTest {
    private val usual = SignalPath.Inputs(
        local = false, codec = "Opus", bitrateKbps = 160, levellingDb = -3.1f, equalizerOn = false,
        spatial = SpatialAudioMode.HEADPHONES, speed = 1f, pitch = 1f, skipSilence = false,
        output = "Galaxy Buds", outputRateKhz = "48", volumePercent = 60,
    )

    @Test
    fun `a song through Headphones with its volume levelled`() {
        val stages = SignalPath.stages(usual)
        assertEquals(
            listOf(Kind.UNTOUCHED, Kind.CHANGED, Kind.UNTOUCHED, Kind.CHANGED, Kind.UNTOUCHED, Kind.WHERE, Kind.WHERE),
            stages.map { it.kind },
        )
        assertEquals(listOf("Opus", 160), stages[0].args)
        assertEquals(R.string.signal_turned_down, stages[1].detail)
        assertEquals(listOf("3.1"), stages[1].args)
        assertEquals(R.string.signal_headphones, stages[3].detail)
        assertEquals(listOf("Galaxy Buds", "48"), stages[5].args)
        assertEquals(listOf(60), stages[6].args)
    }

    @Test
    fun `levelling off, or not needed, leaves the sound untouched`() {
        val off = SignalPath.stages(usual.copy(levellingDb = null))[1]
        assertEquals(Kind.UNTOUCHED, off.kind)
        assertEquals(R.string.signal_off, off.detail)
        val none = SignalPath.stages(usual.copy(levellingDb = -0.04f))[1]
        assertEquals(Kind.UNTOUCHED, none.kind)
        assertEquals(R.string.signal_not_needed, none.detail)
    }

    @Test
    fun `speed, pitch and skipped silence change the sound`() {
        val stages = SignalPath.stages(usual.copy(speed = 1.25f, skipSilence = true))
        assertTrue(stages.any { it.title == R.string.signal_speed_pitch && it.kind == Kind.CHANGED && it.args == listOf("1.25", "1.00") })
        assertTrue(stages.any { it.title == R.string.skip_silence && it.kind == Kind.CHANGED })
    }

    @Test
    fun `codecs by their own names`() {
        assertEquals("Opus", SignalPath.codecName("audio/webm", "opus"))
        assertEquals("AAC", SignalPath.codecName("audio/mp4", "mp4a.40.2"))
        assertEquals("FLAC", SignalPath.codecName("audio/flac", null))
        assertEquals("MP3", SignalPath.codecName("audio/mpeg", ""))
        assertEquals(null, SignalPath.codecName(null, null))
        assertEquals(-3.1f, SignalPath.decibels(0.7f), 0.05f)
    }

    @Test
    fun `it comes after the menu items people know the place of`() {
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt").readText()
        val advanced = menu.indexOf("title = R.string.advanced")
        val signal = menu.indexOf("title = R.string.signal_path")
        val tracking = menu.indexOf("title = R.string.head_tracking_recentre")
        assertTrue("Signal path moved the items around it", advanced in 0 until signal && signal < tracking)
    }
}
