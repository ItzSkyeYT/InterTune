/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A video offered in several languages plays in the one it was made in (#151, OriginalAudio).
 *
 * The answers are YouTube's own, asked as the app asks (VISIONOS, signed out) on 12 Nov 2026 and
 * kept in src/test/resources/player with the addresses reduced to their shape and everything of
 * the session taken out (experiments/bugs/issue-151: fetch_player.py, trim_fixture.py):
 * - a podcast episode with YouTube's machine dubs: 105 audio streams, five for each of 21
 *   languages, the original English (US);
 * - a podcast episode with dubs its channel uploaded: 110 streams, 22 languages, English;
 * - a podcast clip in one language: ten streams, each of the five also with stable volume.
 *
 * Before, the largest stream of them all played: the Italian machine dub of the first, the
 * Filipino dub of the second.
 */
class OriginalAudioTest {

    private fun audioOf(name: String): List<PlayerResponse.StreamingData.Format> {
        val text = requireNotNull(javaClass.getResourceAsStream("/player/$name")) { "missing fixture $name" }.readBytes().decodeToString()
        return YouTube.decodePlayerAnswer(text).streamingData!!.adaptiveFormats.filter { it.isAudio }
    }

    private val machineDubbed by lazy { audioOf("auto-dubbed-8e3RC1L5V_Y.json") }
    private val dubbed by lazy { audioOf("dubbed-IRCZ1Mt2a8M.json") }
    private val oneLanguage by lazy { audioOf("one-language-3viWJA6mMmE.json") }

    private fun pick(offer: List<PlayerResponse.StreamingData.Format>, quality: AudioQuality, metered: Boolean = false) =
        requireNotNull(AudioPick.choose(offer, quality, metered)) { "nothing chosen" }

    /** The largest stream of all as the higher tiers count them, which is what they took before. */
    private fun largest(offer: List<PlayerResponse.StreamingData.Format>) = offer.maxWith(compareBy { AudioPick.worth(it) })

    /** [format] with nothing that says what its sound is but its language. */
    private fun PlayerResponse.StreamingData.Format.unmarked(default: Boolean = false) = copy(
        xtags = null,
        audioTrack = audioTrack?.copy(displayName = audioTrack?.displayName?.replace(" original", ""), audioIsDefault = default),
    )

    @Test
    fun `YouTube's answer is read with each stream's language and what its sound is`() {
        assertEquals(105, machineDubbed.size)
        assertEquals(21, machineDubbed.mapNotNull { it.audioTrack?.id }.distinct().size)
        assertEquals(mapOf("dubbed-auto" to 100, "original" to 5), machineDubbed.groupingBy { OriginalAudio.contentOf(it) }.eachCount())
        val original = machineDubbed.first { OriginalAudio.isOriginal(it) }
        assertEquals(PlayerResponse.StreamingData.Format.AudioTrack("en-US.4", "English (US) original", true), original.audioTrack)
        assertEquals(mapOf("acont" to "original", "lang" to "en-US"), OriginalAudio.xtagsOf(original.xtags))
        assertTrue("only the original is the default", machineDubbed.filter { it.audioTrack?.audioIsDefault == true }.all { OriginalAudio.isOriginal(it) })

        assertEquals(110, dubbed.size)
        assertEquals(22, dubbed.mapNotNull { it.audioTrack?.id }.distinct().size)
        assertEquals(mapOf("dubbed" to 105, "original" to 5), dubbed.groupingBy { OriginalAudio.contentOf(it) }.eachCount())

        assertEquals(10, oneLanguage.size)
        assertTrue(oneLanguage.all { it.audioTrack == null && OriginalAudio.contentOf(it) == null })
        assertEquals("the stable volume ones say so", 5, oneLanguage.count { OriginalAudio.xtagsOf(it.xtags) == mapOf("drc" to "1") })
    }

    @Test
    fun `the largest stream of a dubbed video is a dub, which is what played before`() {
        largest(machineDubbed).let {
            assertEquals("it.10", it.audioTrack?.id)
            assertEquals(147_252, it.bitrate)
            assertTrue(OriginalAudio.isDub(it))
        }
        largest(dubbed).let {
            assertEquals("fil.3", it.audioTrack?.id)
            assertEquals(169_620, it.bitrate)
            assertTrue(OriginalAudio.isDub(it))
        }
    }

    @Test
    fun `every quality tier takes the original, on any connection`() {
        for ((offer, original) in listOf(machineDubbed to "en-US.4", dubbed to "en.4")) {
            for (quality in AudioQuality.entries) {
                for (metered in listOf(false, true)) {
                    val taken = pick(offer, quality, metered)
                    assertEquals("$quality, metered $metered", original, taken.audioTrack?.id)
                    assertTrue(OriginalAudio.isOriginal(taken))
                }
            }
        }
    }

    @Test
    fun `the tiers choose among the original's streams as they do among a song's`() {
        // The original's five: 139 at 52 kbps, 140 at 132, 249 at 63, 250 at 82, 251 at 145.
        assertEquals(251 to 145_440, pick(machineDubbed, AudioQuality.MAX).let { it.itag to it.bitrate })
        assertEquals(251, pick(machineDubbed, AudioQuality.HIGH, metered = true).itag)
        assertEquals(251, pick(machineDubbed, AudioQuality.AUTO).itag)
        assertEquals(250 to 81_862, pick(machineDubbed, AudioQuality.AUTO, metered = true).let { it.itag to it.bitrate })
        assertEquals(139 to 51_822, pick(machineDubbed, AudioQuality.LOW).let { it.itag to it.bitrate })

        // 139 at 55 kbps, 140 at 135, 249 at 60, 250 at 80, 251 at 143.
        assertEquals(251 to 143_014, pick(dubbed, AudioQuality.MAX).let { it.itag to it.bitrate })
        assertEquals(250 to 79_558, pick(dubbed, AudioQuality.AUTO, metered = true).let { it.itag to it.bitrate })
        assertEquals(249 to 59_544, pick(dubbed, AudioQuality.LOW).let { it.itag to it.bitrate })
    }

    @Test
    fun `a video in one language is chosen among as before, every stream of it`() {
        assertSame(oneLanguage, OriginalAudio.of(oneLanguage))
        // As it was: the largest, which here is the stable volume one, and the smallest.
        assertEquals(251 to 154_818, pick(oneLanguage, AudioQuality.MAX).let { it.itag to it.bitrate })
        assertEquals(250 to 89_807, pick(oneLanguage, AudioQuality.AUTO, metered = true).let { it.itag to it.bitrate })
        assertEquals(249 to 55_572, pick(oneLanguage, AudioQuality.LOW).let { it.itag to it.bitrate })
    }

    @Test
    fun `with no stream called original the dubs are set aside`() {
        // The original with its marks taken off, among its machine dubs.
        val offer = machineDubbed.map { if (OriginalAudio.isOriginal(it)) it.unmarked() else it }
        val among = OriginalAudio.of(offer)
        assertEquals(5, among.size)
        assertTrue(among.all { it.audioTrack?.id == "en-US.4" })
        assertEquals("en-US.4", pick(offer, AudioQuality.MAX).audioTrack?.id)
    }

    @Test
    fun `with no stream called original and several that are no dub, the default language is taken`() {
        val offer = machineDubbed.map {
            when (it.audioTrack?.id) {
                "en-US.4" -> it.unmarked()
                "es-US.10" -> it.unmarked(default = true)
                else -> it
            }
        }
        val among = OriginalAudio.of(offer)
        assertEquals(5, among.size)
        assertTrue(among.all { it.audioTrack?.id == "es-US.10" })
    }

    @Test
    fun `with no xtags the name says which is the original`() {
        val offer = machineDubbed.map { it.copy(xtags = null) }
        val among = OriginalAudio.of(offer)
        assertEquals(5, among.size)
        assertTrue(among.all { it.audioTrack?.displayName == "English (US) original" })
    }

    @Test
    fun `nothing but dubs, or languages with no mark at all, are chosen among as before, so the video plays`() {
        val dubs = machineDubbed.filter { OriginalAudio.isDub(it) }
        assertEquals(100, dubs.size)
        assertSame(dubs, OriginalAudio.of(dubs))
        assertEquals("it.10", pick(dubs, AudioQuality.MAX).audioTrack?.id)

        val unmarked = machineDubbed.map { it.unmarked() }
        assertEquals(unmarked, OriginalAudio.of(unmarked))
    }

    @Test
    fun `xtags that cannot be read are no mark, and nothing fails`() {
        for (odd in listOf(null, "", "!!!!", "AAAA", "CggKA2Ry", "Cg", "x".repeat(5000))) {
            assertEquals("$odd", emptyMap<String, String>(), OriginalAudio.xtagsOf(odd))
        }
        val odd = machineDubbed.first().copy(xtags = "CggKA2Ry")
        assertNull(OriginalAudio.contentOf(odd))
        assertFalse(OriginalAudio.isDub(odd))
        assertFalse("its name does not say original either", OriginalAudio.isOriginal(odd))
        // Padded as well as not, as base64url may be.
        assertEquals(mapOf("drc" to "1"), OriginalAudio.xtagsOf("CggKA2RyYxIBMQ"))
        assertEquals(mapOf("drc" to "1"), OriginalAudio.xtagsOf("CggKA2RyYxIBMQ=="))
    }

    @Test
    fun `the log says which language was taken and why, and nothing for a video in one`() {
        assertEquals(
            "audio in 21 languages: took en-US.4 (English (US) original), the original",
            OriginalAudio.said(machineDubbed, pick(machineDubbed, AudioQuality.MAX)),
        )
        assertEquals(
            "audio in 21 languages: took it.10 (Italian), the stream the player has bytes of",
            OriginalAudio.said(machineDubbed, largest(machineDubbed), kept = true),
        )
        val dubs = machineDubbed.filter { OriginalAudio.isDub(it) }
        assertEquals(
            "audio in 20 languages: took it.10 (Italian), a dub, none is called the original",
            OriginalAudio.said(dubs, pick(dubs, AudioQuality.MAX)),
        )
        val unmarked = machineDubbed.filter { it.audioTrack?.id in setOf("en-US.4", "es-US.10") }.map { it.unmarked(default = it.audioTrack?.id == "es-US.10") }
        assertEquals(
            "audio in 2 languages: took es-US.10 (Spanish (US)), the default, none is called the original",
            OriginalAudio.said(unmarked, pick(unmarked, AudioQuality.MAX)),
        )
        assertNull(OriginalAudio.said(oneLanguage, pick(oneLanguage, AudioQuality.MAX)))
        assertNull(OriginalAudio.said(machineDubbed, null))
    }
}
