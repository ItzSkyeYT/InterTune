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
}
