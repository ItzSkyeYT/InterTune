/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastMimeTest {

    @Test
    fun `YouTube's formats lose their codecs parameter`() {
        assertEquals("audio/webm", CastMime.bare("audio/webm; codecs=\"opus\""))
        assertEquals("audio/mp4", CastMime.bare("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("audio/mpeg", CastMime.bare(" Audio/MPEG "))
    }

    @Test
    fun `the two streams YouTube serves get their extensions`() {
        assertEquals("webm", CastMime.extensionFor("audio/webm; codecs=\"opus\""))
        assertEquals("m4a", CastMime.extensionFor("audio/mp4; codecs=\"mp4a.40.2\""))
    }

    @Test
    fun `local file types go both ways`() {
        for ((extension, mime) in listOf(
            "mp3" to "audio/mpeg", "flac" to "audio/flac", "ogg" to "audio/ogg", "wav" to "audio/wav",
            "m4a" to "audio/mp4", "webm" to "audio/webm", "aac" to "audio/aac",
        )) {
            assertEquals(extension, mime, CastMime.forExtension(extension))
            assertEquals(mime, extension, CastMime.extensionFor(mime))
        }
    }

    @Test
    fun `an extension is read without its dot and without regard to case`() {
        assertEquals("audio/flac", CastMime.forExtension(".FLAC"))
        assertEquals("audio/ogg", CastMime.forExtension("opus"))
        assertNull(CastMime.forExtension("txt"))
        assertNull(CastMime.forExtension(""))
    }

    @Test
    fun `a type not known gets a neutral extension`() {
        assertEquals("bin", CastMime.extensionFor("application/x-nothing"))
        assertEquals("bin", CastMime.extensionFor(""))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `WebM is told apart from other Matroska by its doc type`() {
        val webm = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81, 0x01, 0x42, 0x82, 0x84) + ascii("webm")
        val mka = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81, 0x01, 0x42, 0x82, 0x88) + ascii("matroska")
        assertEquals("audio/webm", CastMime.sniff(webm))
        assertEquals("audio/x-matroska", CastMime.sniff(mka))
    }

    @Test
    fun `the containers the app plays are recognised`() {
        assertEquals("audio/mp4", CastMime.sniff(bytes(0, 0, 0, 0x18) + ascii("ftypdash")))
        assertEquals("audio/flac", CastMime.sniff(ascii("fLaC") + bytes(0, 0, 0, 0x22)))
        assertEquals("audio/ogg", CastMime.sniff(ascii("OggS") + bytes(0, 2)))
        assertEquals("audio/wav", CastMime.sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WAVEfmt ")))
        assertEquals("audio/aiff", CastMime.sniff(ascii("FORM") + bytes(1, 2, 3, 4) + ascii("AIFF")))
        assertEquals("audio/amr", CastMime.sniff(ascii("#!AMR\n")))
        assertEquals("audio/mpeg", CastMime.sniff(ascii("ID3") + bytes(4, 0, 0)))
    }

    @Test
    fun `ADTS and bare MP3 frames are told apart by the layer bits`() {
        assertEquals("audio/aac", CastMime.sniff(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals("audio/aac", CastMime.sniff(bytes(0xFF, 0xF9, 0x50, 0x80)))
        assertEquals("audio/mpeg", CastMime.sniff(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals("audio/mpeg", CastMime.sniff(bytes(0xFF, 0xF3, 0x90, 0x64)))
    }

    @Test
    fun `nothing known, or too little, says nothing`() {
        assertNull(CastMime.sniff(ByteArray(0)))
        assertNull(CastMime.sniff(ascii("Og")))
        assertNull(CastMime.sniff(ascii("<html>")))
        assertNull(CastMime.sniff(ascii("RIFF1234AVI ")))
    }
}
