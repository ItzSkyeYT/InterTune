/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.AudioPick
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.metrolist.innertubex.models.response.PlayerResponse as SabrPlayerResponse

/**
 * A video in several languages started over SABR is started in the language the walk chose (#151).
 *
 * SabrOver reads the walk's answer a second time, into InnerTubeX's model, and has to find the
 * walk's stream in it ([SabrOver.sameFormat]). Every language is offered under the same itags, so
 * the itag alone names 21 streams of the podcast episode in the fixture, the first of them Arabic.
 * The answers are YouTube's own: see OriginalAudioTest.
 */
class SabrLanguageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun textOf(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/player/$name")) { "missing fixture $name" }.readBytes().decodeToString()

    private fun ours(name: String) = YouTube.decodePlayerAnswer(textOf(name)).streamingData!!.adaptiveFormats.filter { it.isAudio }

    private fun theirs(name: String) = json.decodeFromString(SabrPlayerResponse.serializer(), textOf(name)).streamingData!!.adaptiveFormats

    private val dubbed = "auto-dubbed-8e3RC1L5V_Y.json"
    private val oneLanguage = "one-language-3viWJA6mMmE.json"

    /** What the walk takes at the highest quality. */
    private fun chosen(name: String) = requireNotNull(AudioPick.choose(ours(name), AudioQuality.MAX, metered = false))

    @Test
    fun `the stream started over SABR is the one the walk chose, the original`() {
        val chosen = chosen(dubbed)
        val started = requireNotNull(SabrOver.sameFormat(theirs(dubbed), chosen))
        assertEquals("en-US.4", started.audioTrack?.id)
        assertEquals(251, started.itag)
        assertEquals(chosen.contentLength, started.contentLength)
        // What InnerTubeX then asks YouTube for the stream by (SabrBootstrapFactory: the format's xtags and its audio track's id).
        assertEquals(chosen.xtags, started.xtags)
    }

    @Test
    fun `with no upload time to tell the streams apart it is still that language, not the first listed`() {
        val chosen = chosen(dubbed).copy(lastModified = null)
        assertEquals("the first of the itag, which was taken before", "ar.10", theirs(dubbed).first { it.itag == 251 }.audioTrack?.id)
        assertEquals("en-US.4", SabrOver.sameFormat(theirs(dubbed), chosen)?.audioTrack?.id)
    }

    @Test
    fun `a language the answer does not have is not started in another`() {
        val chosen = chosen(dubbed).let { it.copy(audioTrack = PlayerResponse.StreamingData.Format.AudioTrack("xx.1", "No such language", false)) }
        assertNull(SabrOver.sameFormat(theirs(dubbed), chosen))
    }

    @Test
    fun `a video in one language is found as before, by its itag and upload time, or its itag`() {
        val chosen = chosen(oneLanguage)
        val started = requireNotNull(SabrOver.sameFormat(theirs(oneLanguage), chosen))
        assertEquals(chosen.contentLength, started.contentLength)
        assertTrue("the stable volume stream the walk took", started.isDrc)
        val first = theirs(oneLanguage).first { it.itag == chosen.itag }
        assertEquals(first, SabrOver.sameFormat(theirs(oneLanguage), chosen.copy(lastModified = null)))
        assertNull(SabrOver.sameFormat(theirs(oneLanguage), chosen.copy(itag = 774)))
    }
}
