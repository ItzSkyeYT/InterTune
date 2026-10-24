/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The output's short buffer is for head tracking that will run, decided each time the output is
 * set up, not for anyone who once turned the switch on. See OutputBufferSize.
 */
class OutputBufferSizeTest {

    private val normalUs = MusicService.DEFAULT_BUFFER_US
    private val shortUs = MusicService.LOW_LATENCY_BUFFER_US

    /** What the output asks for a 48 kHz stereo 16-bit stream on a phone whose own minimum is 5 ms. */
    private fun bytes(provider: DefaultAudioSink.AudioTrackBufferSizeProvider): Int =
        provider.getBufferSizeInBytes(960, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM, 4, 48_000, -1, 1.0)

    private fun media3(minUs: Int) = DefaultAudioTrackBufferSizeProvider.Builder().setMinPcmBufferDurationUs(minUs).build()

    @Test
    fun `the short buffer only while tracking can follow the listener`() {
        var asked = 0
        val connected = { asked++; true }
        assertTrue(OutputBufferSize.wantShort(trackingWanted = true, headphones = true, trackerConnected = connected))
        assertFalse("Spatial audio is not on Headphones", OutputBufferSize.wantShort(true, false, connected))
        assertFalse("the switch is off", OutputBufferSize.wantShort(false, true, connected))
        assertEquals("the tracker is asked only when the rest says yes", 1, asked)
        assertFalse("no tracker connected", OutputBufferSize.wantShort(true, true) { false })
    }

    @Test
    fun `each set-up of the output asks again`() {
        var tracking = false
        val provider = OutputBufferSize(normalUs, shortUs) { tracking }
        assertEquals(bytes(media3(normalUs)), bytes(provider))
        tracking = true
        assertEquals(bytes(media3(shortUs)), bytes(provider))
        tracking = false
        assertEquals("the switch let go, the next song is set up with the usual buffer", bytes(media3(normalUs)), bytes(provider))
        assertTrue("90 ms is shorter than 250 ms", bytes(media3(shortUs)) < bytes(media3(normalUs)))
    }

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `both of the player's outputs ask, and nothing reads the switch once for good`() {
        assertEquals(2, Regex("""\.setAudioTrackBufferSizeProvider\(outputBufferSize\)""").findAll(service).count())
        assertFalse("lowLatencyAudio" in service)
        assertTrue(
            Regex("""OutputBufferSize\.wantShort\(headTrackingWanted, binauralProcessor\.enabled\) \{ headTracking\.isAvailable \}""")
                .containsMatchIn(service),
        )
        // Asked on the player's thread, set by the observer in onCreate; read at construction so
        // the first song's output already knows it.
        assertTrue(
            Regex("""@Volatile\s+private var headTrackingWanted = dataStore\.get\(HeadTrackingKey, false\)""").containsMatchIn(service),
        )
    }
}
