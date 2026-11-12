/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opus where the receiver says it plays it, AAC otherwise. */
class CastFormatsTest {
    private val tv = FormatSupport.Listed(setOf("audio/mpeg", "audio/x-m4a", "audio/flac"))

    @Test
    fun `types are matched across their spellings`() {
        assertTrue(CastFormats.plays(setOf("audio/x-m4a"), "audio/mp4"))
        assertTrue(CastFormats.plays(setOf("video/mp4"), "audio/mp4"))
        assertTrue(CastFormats.plays(setOf("audio/x-matroska"), "audio/webm; codecs=\"opus\""))
        assertTrue(CastFormats.plays(setOf("video/webm"), "audio/webm"))
        assertTrue(CastFormats.plays(setOf("audio/mp3"), "audio/mpeg"))
        assertTrue(CastFormats.plays(setOf("audio/x-flac"), "audio/flac"))
        assertFalse(CastFormats.plays(setOf("audio/ogg"), "audio/webm"))
        assertFalse(CastFormats.plays(setOf("audio/aac"), "audio/mp4"))
        assertFalse(CastFormats.plays(setOf("audio/mpeg"), "application/octet-stream"))
    }

    @Test
    fun `opus goes as it is where it is listed, as AAC where only MP4 is`() {
        val webmTv = FormatSupport.Listed(setOf("audio/webm", "audio/mp4"))
        assertEquals(FormatChoice.AsIs, CastFormats.choose(webmTv, "audio/webm", canFetchAac = true))
        assertEquals(FormatChoice.Aac, CastFormats.choose(tv, "audio/webm", canFetchAac = true))
        assertEquals(FormatChoice.AsIs, CastFormats.choose(tv, "audio/mp4", canFetchAac = true))
    }

    @Test
    fun `a local file goes as it is or not at all`() {
        assertEquals(FormatChoice.AsIs, CastFormats.choose(tv, "audio/flac", canFetchAac = false))
        assertEquals(FormatChoice.Unplayable("audio/ogg"), CastFormats.choose(tv, "audio/ogg", canFetchAac = false))
        assertEquals(FormatChoice.Unplayable("audio/webm"), CastFormats.choose(tv, "audio/webm", canFetchAac = false))
    }

    @Test
    fun `a renderer that takes neither our type nor MP4 cannot be sent the song`() {
        val mp3Only = FormatSupport.Listed(setOf("audio/mpeg"))
        assertEquals(FormatChoice.Unplayable("audio/webm"), CastFormats.choose(mp3Only, "audio/webm; codecs=\"opus\"", canFetchAac = true))
    }

    @Test
    fun `a receiver that said nothing gets the song as it is, and one retry as AAC`() {
        val unknown = FormatSupport.Unknown
        assertEquals(FormatChoice.AsIs, CastFormats.choose(unknown, "audio/webm", canFetchAac = true))
        assertTrue(CastFormats.retryAsAac(unknown, FormatChoice.AsIs, "audio/webm", canFetchAac = true))
        assertFalse(CastFormats.retryAsAac(unknown, FormatChoice.Aac, "audio/webm", canFetchAac = true))
        assertFalse(CastFormats.retryAsAac(unknown, FormatChoice.AsIs, "audio/mp4", canFetchAac = true))
        assertFalse(CastFormats.retryAsAac(unknown, FormatChoice.AsIs, "audio/webm", canFetchAac = false))
        assertFalse(CastFormats.retryAsAac(tv, FormatChoice.AsIs, "audio/webm", canFetchAac = true))
    }

    @Test
    fun `a type a silent receiver failed and then played as AAC goes as AAC from then on`() {
        val unknown = FormatSupport.Unknown
        val refused = setOf("audio/webm")
        assertEquals(FormatChoice.Aac, CastFormats.choose(unknown, "audio/webm; codecs=\"opus\"", canFetchAac = true, refused = refused))
        assertEquals(FormatChoice.AsIs, CastFormats.choose(unknown, "audio/mp4", canFetchAac = true, refused = refused))
        assertEquals(FormatChoice.AsIs, CastFormats.choose(unknown, "audio/flac", canFetchAac = true, refused = refused))
        // A file on the phone has only itself to send.
        assertEquals(FormatChoice.AsIs, CastFormats.choose(unknown, "audio/webm", canFetchAac = false, refused = refused))
        // A receiver that listed what it plays is taken at its word.
        assertEquals(FormatChoice.AsIs, CastFormats.choose(FormatSupport.Listed(setOf("audio/webm")), "audio/webm", canFetchAac = true, refused = refused))
    }

    @Test
    fun `a renderer that names AAC only by its bare names gets AAC in MP4`() {
        // A Zeppelin Wireless that plays M4A was refused every song before this (3 Nov 2026).
        val speaker = FormatSupport.Listed(setOf("audio/mpeg", "audio/aac", "audio/x-ms-wma"))
        assertEquals(FormatChoice.Aac, CastFormats.choose(speaker, "audio/webm; codecs=\"opus\"", canFetchAac = true))
        assertEquals(FormatChoice.AsIs, CastFormats.choose(speaker, "audio/mp4", canFetchAac = true))
        assertEquals(FormatChoice.AsIs, CastFormats.choose(speaker, "audio/mp4", canFetchAac = false))
        assertEquals(FormatChoice.Unplayable("audio/webm"), CastFormats.choose(speaker, "audio/webm", canFetchAac = false))
        for (name in listOf("audio/x-aac", "audio/aacp", "audio/vnd.dlna.adts")) {
            assertEquals(name, FormatChoice.Aac, CastFormats.choose(FormatSupport.Listed(setOf(name)), "audio/webm", canFetchAac = true))
        }
    }

    @Test
    fun `the track is described in the receiver's own words`() {
        assertEquals("audio/x-m4a", CastFormats.spelling(setOf("audio/mpeg", "audio/x-m4a"), "audio/mp4"))
        assertEquals("audio/aac", CastFormats.spelling(setOf("audio/mpeg", "audio/aac"), "audio/mp4"))
        assertEquals("video/x-matroska", CastFormats.spelling(setOf("video/x-matroska"), "audio/webm; codecs=\"opus\""))
        assertEquals("audio/mp4", CastFormats.spelling(setOf("audio/mp4", "audio/aac"), "audio/mp4"))
        assertEquals("audio/flac", CastFormats.spelling(setOf("audio/mpeg"), "audio/flac"))
        // Raw audio keeps its rate and channels: they say how to read the bytes.
        assertEquals("audio/L16;rate=44100;channels=2", CastFormats.spelling(setOf("audio/l16"), "audio/L16;rate=44100;channels=2"))
    }

    // Raw audio, his decision of 4 Nov 2026: as it is, then AAC, then L16.

    private val lpcmOnly = ProtocolInfo.support(
        "http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM,http-get:*:audio/L8;rate=44100;channels=2:*",
    )

    @Test
    fun `raw audio comes only when the renderer takes neither the song as it is nor AAC`() {
        val opus = "audio/webm; codecs=\"opus\""
        val withWebm = ProtocolInfo.support("http-get:*:audio/webm:*,http-get:*:audio/L16;rate=48000;channels=2:*")
        assertEquals(FormatChoice.AsIs, CastFormats.choose(withWebm, opus, canFetchAac = true, canDecode = true))
        val withMp4 = ProtocolInfo.support("http-get:*:audio/mp4:*,http-get:*:audio/L16;rate=48000;channels=2:*")
        assertEquals(FormatChoice.Aac, CastFormats.choose(withMp4, opus, canFetchAac = true, canDecode = true))
        val choice = CastFormats.choose(lpcmOnly, opus, canFetchAac = true, canDecode = true)
        assertEquals(FormatChoice.Pcm(PcmTarget(listOf(PcmOffer("audio/L16;rate=44100;channels=2", 44_100, 2, "LPCM")))), choice)
    }

    @Test
    fun `without the flag such a renderer is refused as before, naming the type`() {
        assertEquals(FormatChoice.Unplayable("audio/webm"), CastFormats.choose(lpcmOnly, "audio/webm", canFetchAac = true))
        assertEquals(FormatChoice.Unplayable("audio/webm"), CastFormats.choose(lpcmOnly, "audio/webm", canFetchAac = true, canDecode = false))
    }

    @Test
    fun `a renderer that lists only L8 is refused, since 8-bit is never sent`() {
        val l8Only = ProtocolInfo.support("http-get:*:audio/L8;rate=44100;channels=2:*")
        assertEquals(FormatChoice.Unplayable("audio/webm"), CastFormats.choose(l8Only, "audio/webm", canFetchAac = true, canDecode = true))
    }

    @Test
    fun `a file on the phone can be decoded too, and a receiver that said nothing never gets raw audio`() {
        assertTrue(CastFormats.choose(lpcmOnly, "audio/flac", canFetchAac = false, canDecode = true) is FormatChoice.Pcm)
        assertTrue(CastFormats.choose(lpcmOnly, "audio/mp4", canFetchAac = false, canDecode = true) is FormatChoice.Pcm)
        assertEquals(FormatChoice.AsIs, CastFormats.choose(FormatSupport.Unknown, "audio/webm", canFetchAac = true, canDecode = true))
        // A receiver that took raw audio and failed it is not tried again as AAC.
        assertFalse(CastFormats.retryAsAac(lpcmOnly, FormatChoice.Pcm(PcmTarget.LPCM), "audio/webm", canFetchAac = true))
    }

    @Test
    fun `a renderer that lists WAV and no L16 gets raw audio in a WAV file`() {
        val wavOnly = FormatSupport.Listed(setOf("audio/mpeg", "audio/wav"))
        assertEquals(FormatChoice.Pcm(PcmTarget(emptyList(), "audio/wav")), CastFormats.choose(wavOnly, "audio/webm", canFetchAac = true, canDecode = true))
        // A WAV file of its own still goes as it is.
        assertEquals(FormatChoice.AsIs, CastFormats.choose(wavOnly, "audio/x-wav", canFetchAac = false, canDecode = true))
    }
}
