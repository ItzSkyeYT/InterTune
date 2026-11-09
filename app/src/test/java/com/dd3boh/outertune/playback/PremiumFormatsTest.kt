/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first step of Premium 256 kbps (9 Nov 2026): which audio streams an account is offered, read from
 * the answer a counted play already gets, and a developer row that asks for it on purpose.
 */
class PremiumFormatsTest {

    private fun format(itag: Int, mime: String, bitrate: Int, average: Int? = null, width: Int? = null) = PlayerResponse.StreamingData.Format(
        itag = itag, url = null, mimeType = mime, bitrate = bitrate, width = width, height = null, contentLength = null,
        quality = "tiny", fps = null, qualityLabel = null, averageBitrate = average, audioQuality = null, approxDurationMs = null,
        audioSampleRate = null, audioChannels = null, loudnessDb = null, lastModified = null, signatureCipher = null,
    )

    private val usual = listOf(
        format(251, "audio/webm; codecs=\"opus\"", 160_000, average = 135_400),
        format(140, "audio/mp4; codecs=\"mp4a.40.2\"", 130_000, average = 129_500),
        format(250, "audio/webm; codecs=\"opus\"", 70_000),
        format(137, "video/mp4; codecs=\"avc1.640028\"", 4_000_000, width = 1920),
        format(251, "audio/webm; codecs=\"opus\"", 160_000, average = 135_400),
    )

    @Test
    fun `the audio streams, each once, by itag, with their average rate`() {
        assertEquals(
            listOf(PremiumFormats.Stream(140, "AAC", 130), PremiumFormats.Stream(250, "Opus", 70), PremiumFormats.Stream(251, "Opus", 135)),
            PremiumFormats.audioOf(usual),
        )
        assertEquals(emptyList<PremiumFormats.Stream>(), PremiumFormats.audioOf(null))
    }

    @Test
    fun `a counted play's line says none without Premium streams, and names them with`() {
        assertEquals("Premium formats offered: none", PremiumFormats.offeredLine(usual))
        val premium = usual + format(774, "audio/webm; codecs=\"opus\"", 290_000, average = 256_100) + format(141, "audio/mp4; codecs=\"mp4a.40.2\"", 260_000)
        assertEquals("Premium formats offered: 141 (AAC 260 kbps), 774 (Opus 256 kbps)", PremiumFormats.offeredLine(premium))
        assertEquals("Premium formats offered: none", PremiumFormats.offeredLine(null))
    }

    @Test
    fun `the check lists every audio stream of the account's client`() {
        assertEquals("WEB_REMIX (account): 140 AAC 130k, 250 Opus 70k, 251 Opus 135k", PremiumFormats.streamsLine("WEB_REMIX", usual))
        assertEquals("WEB_REMIX (account): no audio streams", PremiumFormats.streamsLine("WEB_REMIX", emptyList()))
    }

    @Test
    fun `the line after a counted play comes from the answer in hand, with no request of its own`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("asked.answer?.getOrNull()?.let { Log.i(TAG, PremiumFormats.offeredLine(it.streamingData?.adaptiveFormats)) }" in service)
        assertEquals("one account request in the service, the counted play's", 1, Regex("""playerResponseAsAccount\(""").findAll(service).count())
    }

    @Test
    fun `the developer row sits after the history address check, in debug builds only, and asks nothing signed out`() {
        val dev = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DeveloperFrag.kt").readText()
        assertTrue("if (BuildConfig.DEBUG) {\n            HistoryAddressCheckRow()\n            PremiumCheckRow()" in dev)
        val check = File("src/main/java/com/dd3boh/outertune/playback/PremiumCheck.kt").readText()
        val signedOut = check.substringAfter("if (YouTube.cookie == null) {").substringBefore("}")
        assertTrue(signedOut, "return@withContext lines" in signedOut)
        assertTrue(check.indexOf("if (YouTube.cookie == null)") < check.indexOf("playerResponseAsAccount("))
    }
}
