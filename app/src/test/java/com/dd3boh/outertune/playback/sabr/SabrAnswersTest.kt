/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import com.zionhuang.innertube.PlayerAnswers
import com.zionhuang.innertube.models.YouTubeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player answers a song is started over SABR from: only answers that can be streamed so, one
 * per song and client as it asked, each with when it came.
 */
class SabrAnswersTest {
    private val ok = """{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"21540",""" +
        """"serverAbrStreamingUrl":"https://rr1---sn-test.googlevideo.com/videoplayback?sabr=1"}}"""
    private val botCheck = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"""

    @Test
    fun `a refusal never takes the place of an OK answer, and VISIONOS's other identities each keep their own`() {
        val answers = PlayerAnswers()
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS, ok, at = 1_000))
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS_1_04, ok, at = 2_000))
        // The second pass asks VISIONOS again under a new visitorData, and gets the bot check.
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS, botCheck, at = 3_000))
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS_SAFARI, botCheck, at = 4_000))
        val kept = answers.of(SONG, "VISIONOS")
        assertEquals("newest first", listOf(YouTubeClient.VISIONOS_1_04, YouTubeClient.VISIONOS), kept.map { it.client })
        assertTrue(kept.all { PlayerAnswers.streamsOverSabr(it.text) })
        assertFalse(PlayerAnswers.streamsOverSabr(botCheck))
        assertEquals(YouTubeClient.VISIONOS_1_04, answers.latest(SONG, "VISIONOS")?.client)
    }

    @Test
    fun `a newer OK answer of the same client takes the older one's place`() {
        val answers = PlayerAnswers()
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS, ok, at = 1_000))
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS, ok, at = 5_000))
        assertEquals(listOf(5_000L), answers.of(SONG, "VISIONOS").map { it.at })
    }

    @Test
    fun `an answer from before the time asked for is not handed out`() {
        val answers = PlayerAnswers()
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS, ok, at = 1_000))
        assertTrue(answers.of(SONG, "VISIONOS", since = 1_001).isEmpty())
        assertNull(answers.latest(SONG, "VISIONOS", since = 1_001))
        assertEquals(1, answers.of(SONG, "VISIONOS", since = 1_000).size)
    }

    @Test
    fun `answers that cannot be streamed over SABR take no room`() {
        val answers = PlayerAnswers(capacity = 2)
        answers.put(PlayerAnswers.Answer(SONG, YouTubeClient.VISIONOS, ok))
        // The main client's bot check, song after song, and IOS refused: none of it is kept.
        repeat(5) { answers.put(PlayerAnswers.Answer("OTHERSONG0$it", YouTubeClient.IOS, botCheck)) }
        assertNotNull(answers.latest(SONG, "VISIONOS"))
        answers.put(PlayerAnswers.Answer("NEXTSONG001", YouTubeClient.VISIONOS, ok))
        answers.put(PlayerAnswers.Answer("NEXTSONG002", YouTubeClient.VISIONOS, ok))
        assertNull("the oldest goes once two newer are kept", answers.latest(SONG, "VISIONOS"))
    }

    /**
     * On a day YouTube refuses the direct addresses, every /player answer is OK and names a SABR
     * address, whatever the client. Three downloads walk the chain at once, and the eight places
     * went to their other clients' answers before any walk reached its last rung.
     */
    @Test
    fun `three songs walked at once each still have their VISIONOS answers when their rung reads them`() {
        val answers = PlayerAnswers()
        val walk = listOf(
            YouTubeClient.ANDROID_VR_NO_AUTH, YouTubeClient.VISIONOS, YouTubeClient.VISIONOS_1_04, YouTubeClient.VISIONOS_SAFARI,
            YouTubeClient.WEB_REMIX, YouTubeClient.IOS, YouTubeClient.ANDROID,
        )
        val songs = listOf("WALKSONG001", "WALKSONG002", "WALKSONG003")
        var at = 1_000L
        for (client in walk) for (song in songs) answers.put(PlayerAnswers.Answer(song, client, ok, at = at++))
        for (song in songs) {
            assertEquals(
                song,
                setOf(YouTubeClient.VISIONOS, YouTubeClient.VISIONOS_1_04, YouTubeClient.VISIONOS_SAFARI),
                answers.of(song, "VISIONOS").map { it.client }.toSet(),
            )
        }
    }

    @Test
    fun `a start runs out when its answer's address does, counted from when the answer came`() {
        assertEquals(21_540L - 600, SabrOver.secondsLeft(answeredAt = 1_000_000, expiresInSeconds = 21_540, now = 1_000_000 + 600_000))
        assertTrue("run out", SabrOver.secondsLeft(answeredAt = 0, expiresInSeconds = 10, now = 20_000) <= 0)
    }

    private companion object {
        const val SONG = "ANSWERSONG1"
    }
}
