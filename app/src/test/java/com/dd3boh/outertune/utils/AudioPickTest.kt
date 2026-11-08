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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream each quality tier takes (AudioPick), from offers shaped like YouTube's. The free offer
 * is the one measured on 28 Oct 2026 (experiments/bugs/57-opus/probe.py): on Bohemian Rhapsody the
 * AAC stream and the Opus one were both 148 kbps, and Highest took the AAC (#57). The logged offer
 * is the one in the debug log, and what YouTube sent for each of 15 public songs on 8 Nov 2026 give
 * or take a few kbps: the same five streams every time, 139 and 249 at 49 to 63 kbps, 250 at 65 to
 * 80, 140 at 130 to 148 and 251 at 132 to 154 (#88).
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

    private val logged = listOf(
        stream(139, heAac, 50_000, 22050),
        stream(140, aac, 130_000, 44100),
        stream(249, opus, 60_000),
        stream(250, opus, 75_000),
        stream(251, opus, 145_000),
    )

    private val premiumExtras = listOf(stream(141, aac, 256_000, 44100), stream(774, opus, 256_000))

    private fun List<PlayerResponse.StreamingData.Format>.without(itag: Int) = filter { it.itag != itag }

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
    fun `Low takes the smallest, Opus first between near equals`() {
        assertEquals(249, pick(free, AudioQuality.LOW))
        assertEquals(249, pick(logged, AudioQuality.LOW))
        assertEquals(249, pick(logged, AudioQuality.LOW, metered = true))
        // 249 more than 10 kbps over 139, as on 3 of the 15 songs: Low keeps to 139, as it did.
        assertEquals(139, pick(logged.without(249) + stream(249, opus, 63_000), AudioQuality.LOW))
    }

    @Test
    fun `Auto on metered data takes the middle stream, Opus 250, not the smallest (#88)`() {
        assertEquals(250, pick(logged, AudioQuality.AUTO, metered = true))
        assertEquals(250, pick(free, AudioQuality.AUTO, metered = true))
        assertEquals(250, pick(logged.without(249) + stream(249, opus, 63_000), AudioQuality.AUTO, metered = true))
    }

    @Test
    fun `Auto on Wi-Fi, High and Highest take what they took`() {
        for (offer in listOf(free, logged)) {
            assertEquals(251, pick(offer, AudioQuality.AUTO, metered = false))
            assertEquals(251, pick(offer, AudioQuality.HIGH, metered = false))
            assertEquals(251, pick(offer, AudioQuality.HIGH, metered = true))
            assertEquals(251, pick(offer, AudioQuality.MAX, metered = false))
            assertEquals(251, pick(offer, AudioQuality.MAX, metered = true))
        }
    }

    @Test
    fun `without 250 Auto on metered data still takes a middle one`() {
        // Ranked 139, 249, 140, 251: of the two in the middle the lower, 249, not the 130 kbps AAC.
        assertEquals(249, pick(logged.without(250), AudioQuality.AUTO, metered = true))
        assertEquals(250, pick(logged.without(249), AudioQuality.AUTO, metered = true))
        assertEquals(250, pick(logged.without(139), AudioQuality.AUTO, metered = true))
        assertEquals(250, pick(logged.without(139).without(140), AudioQuality.AUTO, metered = true))
    }

    @Test
    fun `of two streams Auto on metered data takes the one Low takes, and of one that one`() {
        // AAC alone, as for a cast.
        assertEquals(139, pick(logged.without(249).without(250).without(251), AudioQuality.AUTO, metered = true))
        assertEquals(249, pick(listOf(stream(251, opus, 145_000), stream(249, opus, 60_000)), AudioQuality.AUTO, metered = true))
        // About the same size: the Opus one, as Low takes it, rather than the AAC one worth less.
        assertEquals(251, pick(free.without(139).without(249).without(250), AudioQuality.AUTO, metered = true))
        assertEquals(251, pick(listOf(stream(251, opus, 145_000)), AudioQuality.AUTO, metered = true))
        assertNull(pick(emptyList(), AudioQuality.AUTO, metered = true))
    }

    @Test
    fun `with a Premium account's streams on offer as well, the middle moves up with them`() {
        // 141 added: six streams, and the lower of the two middles is still 250.
        assertEquals(250, pick(logged + premiumExtras.first(), AudioQuality.AUTO, metered = true))
        // 141 and 774: seven, ranked 139, 249, 250, 140, 251, 141, 774, and the middle is AAC 140.
        assertEquals(140, pick(logged + premiumExtras, AudioQuality.AUTO, metered = true))
    }

    @Test
    fun `on any part of an offer, Auto on metered data sounds no worse than Low and takes the top stream only where Low does`() {
        for (whole in listOf(free, logged + premiumExtras)) {
            for (mask in 1 until (1 shl whole.size)) {
                val offer = whole.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                val auto = AudioPick.choose(offer, AudioQuality.AUTO, metered = true)!!
                val low = AudioPick.choose(offer, AudioQuality.LOW, metered = true)!!
                val top = AudioPick.choose(offer, AudioQuality.HIGH, metered = true)!!
                val shown = offer.map { it.itag }
                assertTrue("$shown: Auto ${auto.itag} under Low's ${low.itag}", AudioPick.worth(auto) >= AudioPick.worth(low))
                if (offer.size > 1 && auto == top) assertEquals("$shown: Auto takes the top stream", low.itag, auto.itag)
            }
        }
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
