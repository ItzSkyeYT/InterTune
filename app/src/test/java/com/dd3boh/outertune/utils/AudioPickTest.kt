/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stream each quality tier takes (AudioPick), from offers shaped like YouTube's. The free offer
 * is the one measured on 28 Oct 2026 (experiments/bugs/57-opus/probe.py): on Bohemian Rhapsody the
 * AAC stream and the Opus one were both 148 kbps, and Highest took the AAC (#57).
 */
class AudioPickTest {

    private fun stream(itag: Int, mime: String, bitrate: Int, rate: Int = 48000) =
        PlayerResponse.StreamingData.Format(
            itag = itag, url = null, mimeType = mime, bitrate = bitrate, width = null, height = null,
            contentLength = null, quality = "tiny", fps = null, qualityLabel = null, averageBitrate = null,
            audioQuality = null, approxDurationMs = null, audioSampleRate = rate, audioChannels = 2,
            loudnessDb = null, lastModified = null, signatureCipher = null,
        )

    private val opus = "audio/webm; codecs=\"opus\""
    private val aac = "audio/mp4; codecs=\"mp4a.40.2\""
    private val heAac = "audio/mp4; codecs=\"mp4a.40.5\""

    private val free = listOf(
        stream(139, heAac, 54_000, 22050),
        stream(140, aac, 148_400, 44100),
        stream(249, opus, 57_000),
        stream(250, opus, 76_000),
        stream(251, opus, 148_000),
    )

    private fun pick(offer: List<PlayerResponse.StreamingData.Format>, quality: AudioQuality, metered: Boolean = false) =
        AudioPick.choose(offer, quality, metered)?.itag

    @Test
    fun `Highest takes Opus when the AAC stream is the same size or a little larger (#57)`() {
        assertEquals(251, pick(free, AudioQuality.MAX))
    }

    @Test
    fun `High takes Opus even when the AAC stream is clearly larger, as on quiet songs`() {
        val quiet = listOf(stream(140, aac, 130_000, 44100), stream(251, opus, 112_000))
        assertEquals(251, pick(quiet, AudioQuality.HIGH))
        assertEquals(251, pick(quiet, AudioQuality.AUTO, metered = false))
        assertEquals(251, pick(quiet, AudioQuality.MAX))
    }

    @Test
    fun `Highest still takes a Premium account's 256 kbps AAC over the 160 kbps Opus`() {
        val premium = listOf(stream(141, aac, 256_000, 44100), stream(140, aac, 130_000, 44100), stream(251, opus, 160_000))
        assertEquals(141, pick(premium, AudioQuality.MAX))
    }

    @Test
    fun `an Opus stream as large as the AAC one wins at Highest`() {
        val both = listOf(stream(141, aac, 256_000, 44100), stream(774, opus, 256_000))
        assertEquals(774, pick(both, AudioQuality.MAX))
    }

    @Test
    fun `Low and Auto on metered data take the smallest, Opus first between near equals`() {
        assertEquals(249, pick(free, AudioQuality.LOW))
        assertEquals(249, pick(free, AudioQuality.AUTO, metered = true))
    }

    @Test
    fun `Highest and High do not drop on a metered connection`() {
        assertEquals(251, pick(free, AudioQuality.MAX, metered = true))
        assertEquals(251, pick(free, AudioQuality.HIGH, metered = true))
    }

    @Test
    fun `with AAC alone on offer, as for a cast, each tier still gets its size`() {
        val aacOnly = free.filter { it.mimeType.startsWith("audio/mp4") }
        assertEquals(140, pick(aacOnly, AudioQuality.HIGH))
        assertEquals(140, pick(aacOnly, AudioQuality.MAX))
        assertEquals(139, pick(aacOnly, AudioQuality.LOW))
    }

    @Test
    fun `the sample rate breaks a tie`() {
        val tie = listOf(stream(140, aac, 130_000, 44100), stream(141, aac, 130_000, 48000))
        assertEquals(141, pick(tie, AudioQuality.MAX))
    }

    @Test
    fun `nothing on offer gives nothing`() {
        assertNull(pick(emptyList(), AudioQuality.HIGH))
    }
}
