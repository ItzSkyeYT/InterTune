/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import com.dd3boh.outertune.R
import com.dd3boh.outertune.cast.CastDevice
import com.dd3boh.outertune.cast.CastProtocol
import com.dd3boh.outertune.cast.SentForm
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

    private val zeppelin = CastDevice(CastProtocol.AIRPLAY, "airplay:0011", "Zeppelin Wireless", "192.168.1.30", 7000, audioOnly = true)
    private val tv = CastDevice(CastProtocol.CHROMECAST, "cc:1", "Living room TV", "192.168.1.31", 8009)

    @Test
    fun `cast to an AirPlay speaker, which the phone's sound reaches, the speaker is where it plays`() {
        val cast = SignalPath.castOf(zeppelin, SentForm("song", "audio/L16;rate=44100;channels=2", raw = true, rate = 44_100), 25)
        val stages = SignalPath.stages(usual.copy(equalizerOn = true, skipSilence = true, cast = cast))
        val byTitle = stages.associateBy { it.title }
        assertEquals(Kind.CHANGED, byTitle.getValue(R.string.audio_normalization).kind)
        assertEquals(R.string.signal_turned_down, byTitle.getValue(R.string.audio_normalization).detail)
        assertEquals(Kind.CHANGED, byTitle.getValue(R.string.equalizer).kind)
        assertEquals(R.string.signal_on, byTitle.getValue(R.string.equalizer).detail)
        // Made for the phone's own outputs: neither reaches a receiver.
        assertEquals(R.string.signal_not_while_casting, byTitle.getValue(R.string.spatial_audio).detail)
        assertEquals(Kind.UNTOUCHED, byTitle.getValue(R.string.spatial_audio).kind)
        assertEquals(R.string.signal_not_while_casting, byTitle.getValue(R.string.skip_silence).detail)
        val plays = byTitle.getValue(R.string.signal_plays_on)
        assertEquals(R.string.signal_receiver_rate, plays.detail)
        assertEquals(listOf("Zeppelin Wireless", "AirPlay", "44.1"), plays.args)
        // The speaker's volume, not the phone's, which plays no part.
        assertEquals(listOf(25), byTitle.getValue(R.string.cast_volume).args)
        assertTrue(stages.none { it.title == R.string.signal_volume })
    }

    @Test
    fun `cast as it is, the equalizer and the levelling say they do not reach the receiver`() {
        val cast = SignalPath.castOf(tv, SentForm("song", "audio/webm", raw = false), null)
        val stages = SignalPath.stages(usual.copy(equalizerOn = true, cast = cast))
        val byTitle = stages.associateBy { it.title }
        for (title in listOf(R.string.audio_normalization, R.string.equalizer)) {
            assertEquals(Kind.UNTOUCHED, byTitle.getValue(title).kind)
            assertEquals(R.string.signal_not_on_receiver, byTitle.getValue(title).detail)
        }
        val plays = byTitle.getValue(R.string.signal_plays_on)
        assertEquals(R.string.signal_receiver, plays.detail)
        assertEquals(listOf("Living room TV", "Chromecast"), plays.args)
        assertTrue("no volume it did not say", stages.none { it.title == R.string.cast_volume || it.title == R.string.signal_volume })
        // Off is off wherever it plays.
        assertEquals(R.string.signal_off, SignalPath.stages(usual.copy(levellingDb = null, cast = cast))[1].detail)
    }

    @Test
    fun `before the first song has gone, an AirPlay speaker is raw audio at 44 1 kHz and anything else the song as it is`() {
        assertEquals(SignalPath.Cast("Zeppelin Wireless", "AirPlay", "44.1", true, null), SignalPath.castOf(zeppelin, null, null))
        assertEquals(SignalPath.Cast("Living room TV", "Chromecast", null, false, null), SignalPath.castOf(tv, null, null))
        val renderer = CastDevice(CastProtocol.DLNA, "uuid:1", "Zeppelin", "192.168.1.30", 80)
        assertEquals("48", SignalPath.castOf(renderer, SentForm("song", "audio/L16;rate=48000;channels=2", raw = true, rate = 48_000), null).rateKhz)
        assertEquals(null, SignalPath.castOf(renderer, SentForm("song", "audio/mp4", raw = false), null).rateKhz)
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
