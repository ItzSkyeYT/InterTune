/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.constants.DownloadBitrate
import com.dd3boh.outertune.constants.DownloadFormat
import com.dd3boh.outertune.constants.DownloadFormat.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file format of a download and its bitrate (Unreleased.DOWNLOAD_FORMATS): what each format is
 * called on disk, which encoder makes it, what a held-back flag does to the setting, what Auto picks
 * for a stream, and what is done with a song of a given kind. No Android in here.
 */
class DownloadFormatsTest {

    private val tags = SongTags("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"))

    // ---- the formats

    @Test
    fun `each format has its extension, its type and its encoder`() {
        assertEquals(listOf("ORIGINAL", "MP3", "M4A", "FLAC", "WAV"), DownloadFormat.entries.map { it.name })
        assertNull(DownloadFormat.ORIGINAL.extension)
        assertNull(DownloadFormat.ORIGINAL.mimeType)
        assertEquals(Encoder.NONE, DownloadFormat.ORIGINAL.encoder)
        assertEquals(Triple("mp3", "audio/mpeg", Encoder.LAME), DownloadFormat.MP3.let { Triple(it.extension, it.mimeType, it.encoder) })
        assertEquals(Triple("m4a", "audio/mp4", Encoder.AAC), DownloadFormat.M4A.let { Triple(it.extension, it.mimeType, it.encoder) })
        assertEquals(Triple("flac", "audio/flac", Encoder.FLAC), DownloadFormat.FLAC.let { Triple(it.extension, it.mimeType, it.encoder) })
        assertEquals(Triple("wav", "audio/x-wav", Encoder.WAV), DownloadFormat.WAV.let { Triple(it.extension, it.mimeType, it.encoder) })
    }

    @Test
    fun `a song file made by a format is called Artist - Title and the format's extension`() {
        for (format in DownloadFormat.entries.filter { it.extension != null }) {
            assertEquals("Rick Astley - Never Gonna Give You Up.${format.extension}", SongFileNames.of(tags, format.extension!!))
        }
    }

    @Test
    fun `every extension a download is saved as is a song file the folder knows again from its tags`() {
        for (extension in listOf("opus", "m4a", "mp3", "flac", "wav")) {
            assertTrue(extension, SongFileNames.isSongFile("Rick Astley - Never Gonna Give You Up.$extension"))
            assertTrue(extension, SongFileNames.isSongFile("A - B.${extension.uppercase()}"))
        }
        assertFalse(SongFileNames.isSongFile("Never Gonna Give You Up [dQw4w9WgXcQ].mka"))
        // The formats' extensions are all among them: a file the app makes is a file it can find.
        assertTrue(DownloadFormat.entries.mapNotNull { it.extension }.all { it in SongFileNames.EXTENSIONS })
    }

    @Test
    fun `the flag held back means Original, whatever is stored`() {
        for (format in DownloadFormat.entries) {
            assertEquals(DownloadFormat.ORIGINAL, DownloadFormats.effective(format, offered = false))
            assertEquals(format, DownloadFormats.effective(format, offered = true))
        }
    }

    @Test
    fun `a stored name that is no format reads as Original, and a bitrate as Auto`() {
        assertEquals(DownloadFormat.ORIGINAL, DownloadFormats.formatNamed("OGG"))
        assertEquals(DownloadFormat.ORIGINAL, DownloadFormats.formatNamed(null))
        assertEquals(DownloadFormat.FLAC, DownloadFormats.formatNamed("FLAC"))
        assertEquals(DownloadBitrate.AUTO, DownloadFormats.bitrateNamed("K999"))
        assertEquals(DownloadBitrate.AUTO, DownloadFormats.bitrateNamed(null))
        assertEquals(DownloadBitrate.K192, DownloadFormats.bitrateNamed("K192"))
    }

    // ---- the Bitrate row

    @Test
    fun `the Bitrate row is for MP3 and M4A and for nothing else`() {
        assertEquals(setOf(DownloadFormat.MP3, DownloadFormat.M4A), DownloadFormat.entries.filter { DownloadFormats.bitrateRowShown(it) }.toSet())
        assertEquals(setOf(DownloadFormat.MP3, DownloadFormat.M4A), DownloadFormat.entries.filter { it.hasBitrate }.toSet())
    }

    @Test
    fun `the Bitrate choices are Auto, 128, 192, 256 and 320, Auto first`() {
        assertEquals(listOf("AUTO", "K128", "K192", "K256", "K320"), DownloadBitrate.entries.map { it.name })
        assertEquals(listOf(null, 128, 192, 256, 320), DownloadBitrate.entries.map { it.kbps })
    }

    // ---- Auto

    @Test
    fun `a stream under 100 kbps gets 128 kbps in both formats`() {
        for (bps in listOf(48_000L, 48_800L, 50_000L, 70_000L, 75_000L, 99_999L)) {
            assertEquals("MP3 for $bps", 128, AudioBitrate.auto(DownloadFormat.MP3, bps))
            assertEquals("AAC for $bps", 128, AudioBitrate.auto(DownloadFormat.M4A, bps))
        }
    }

    @Test
    fun `a stream up to about 170 kbps gets 256 as an MP3 and 192 as an AAC`() {
        // Opus 251 is 127 to 135 kbps on average, AAC 140 129.5, the largest Opus at Highest about 160.
        for (bps in listOf(100_000L, 127_000L, 129_500L, 135_000L, 160_000L, 170_000L)) {
            assertEquals("MP3 for $bps", 256, AudioBitrate.auto(DownloadFormat.MP3, bps))
            assertEquals("AAC for $bps", 192, AudioBitrate.auto(DownloadFormat.M4A, bps))
        }
    }

    @Test
    fun `a stream above that, Premium's 256 kbps AAC, gets 320 as an MP3 and 256 as an AAC`() {
        for (bps in listOf(170_001L, 256_000L, 260_000L, 320_000L)) {
            assertEquals("MP3 for $bps", 320, AudioBitrate.auto(DownloadFormat.MP3, bps))
            assertEquals("AAC for $bps", 256, AudioBitrate.auto(DownloadFormat.M4A, bps))
        }
    }

    @Test
    fun `a stream whose rate is not known is taken for the usual one`() {
        for (unknown in listOf(null, 0L, -1L)) {
            assertEquals(256, AudioBitrate.auto(DownloadFormat.MP3, unknown))
            assertEquals(192, AudioBitrate.auto(DownloadFormat.M4A, unknown))
        }
    }

    @Test
    fun `Auto is asked of the formats that have a bitrate only`() {
        for (format in listOf(DownloadFormat.ORIGINAL, DownloadFormat.FLAC, DownloadFormat.WAV)) {
            val failed = runCatching { AudioBitrate.auto(format, 135_000L) }.exceptionOrNull()
            assertTrue("$format", failed is IllegalArgumentException)
        }
    }

    @Test
    fun `Auto never asks for more than the format's fixed choices offer`() {
        for (format in listOf(DownloadFormat.MP3, DownloadFormat.M4A)) {
            for (bps in listOf(null, 1L, 48_000L, 99_999L, 100_000L, 170_000L, 170_001L, 1_411_000L)) {
                assertTrue("$format $bps", AudioBitrate.auto(format, bps) in DownloadBitrate.entries.mapNotNull { it.kbps })
            }
        }
    }

    @Test
    fun `a fixed rate is honoured, above Auto's and below it`() {
        assertEquals(256, AudioBitrate.resolve(DownloadBitrate.AUTO, DownloadFormat.MP3, 135_000L))
        assertEquals(320, AudioBitrate.resolve(DownloadBitrate.K320, DownloadFormat.MP3, 135_000L))
        assertEquals(128, AudioBitrate.resolve(DownloadBitrate.K128, DownloadFormat.MP3, 135_000L))
        assertEquals(256, AudioBitrate.resolve(DownloadBitrate.K256, DownloadFormat.M4A, 48_000L))
        assertEquals(192, AudioBitrate.resolve(DownloadBitrate.AUTO, DownloadFormat.M4A, 135_000L))
    }

    @Test
    fun `a rate above what Auto gives is the one a note says cannot add quality`() {
        assertTrue(AudioBitrate.isAboveAuto(DownloadBitrate.K320, DownloadFormat.MP3, 135_000L))
        assertFalse(AudioBitrate.isAboveAuto(DownloadBitrate.K256, DownloadFormat.MP3, 135_000L))
        assertFalse(AudioBitrate.isAboveAuto(DownloadBitrate.AUTO, DownloadFormat.MP3, 135_000L))
        assertTrue(AudioBitrate.isAboveAuto(DownloadBitrate.K192, DownloadFormat.MP3, 48_000L))
        assertTrue(AudioBitrate.isAboveAuto(DownloadBitrate.K256, DownloadFormat.M4A, 135_000L))
        assertFalse(AudioBitrate.isAboveAuto(DownloadBitrate.K192, DownloadFormat.M4A, 135_000L))
    }

    // ---- what is done with a song

    @Test
    fun `Original, or a format made off, saves the song as it was sent`() {
        for (source in SourceCodec.entries) {
            assertEquals(ConvertPlan.AsIs, DownloadFormats.plan(DownloadFormat.ORIGINAL, DownloadBitrate.K320, source, 135_000L))
        }
    }

    @Test
    fun `MP3 from Opus or AAC is encoded at Auto's rate, or at the fixed one`() {
        assertEquals(ConvertPlan.Encode(DownloadFormat.MP3, 256), DownloadFormats.plan(DownloadFormat.MP3, DownloadBitrate.AUTO, SourceCodec.OPUS, 135_000L))
        assertEquals(ConvertPlan.Encode(DownloadFormat.MP3, 128), DownloadFormats.plan(DownloadFormat.MP3, DownloadBitrate.AUTO, SourceCodec.OPUS, 48_600L))
        assertEquals(ConvertPlan.Encode(DownloadFormat.MP3, 256), DownloadFormats.plan(DownloadFormat.MP3, DownloadBitrate.AUTO, SourceCodec.AAC, 129_500L))
        assertEquals(ConvertPlan.Encode(DownloadFormat.MP3, 320), DownloadFormats.plan(DownloadFormat.MP3, DownloadBitrate.K320, SourceCodec.OPUS, 135_000L))
    }

    @Test
    fun `M4A from Opus is encoded as AAC, from AAC it is the Original file and nothing is encoded twice`() {
        assertEquals(ConvertPlan.Encode(DownloadFormat.M4A, 192), DownloadFormats.plan(DownloadFormat.M4A, DownloadBitrate.AUTO, SourceCodec.OPUS, 135_000L))
        assertEquals(ConvertPlan.Encode(DownloadFormat.M4A, 128), DownloadFormats.plan(DownloadFormat.M4A, DownloadBitrate.AUTO, SourceCodec.OPUS, 48_600L))
        assertEquals(ConvertPlan.Encode(DownloadFormat.M4A, 256), DownloadFormats.plan(DownloadFormat.M4A, DownloadBitrate.K256, SourceCodec.OPUS, 48_600L))
        assertEquals(ConvertPlan.AsIs, DownloadFormats.plan(DownloadFormat.M4A, DownloadBitrate.AUTO, SourceCodec.AAC, 129_500L))
        assertEquals(ConvertPlan.AsIs, DownloadFormats.plan(DownloadFormat.M4A, DownloadBitrate.K320, SourceCodec.AAC, 48_800L))
    }

    @Test
    fun `FLAC and WAV have no bitrate to pick`() {
        for (source in listOf(SourceCodec.OPUS, SourceCodec.AAC, SourceCodec.MP3)) {
            assertEquals(ConvertPlan.Encode(DownloadFormat.FLAC, null), DownloadFormats.plan(DownloadFormat.FLAC, DownloadBitrate.K320, source, 135_000L))
            assertEquals(ConvertPlan.Encode(DownloadFormat.WAV, null), DownloadFormats.plan(DownloadFormat.WAV, DownloadBitrate.AUTO, source, 135_000L))
        }
    }

    @Test
    fun `a file already in the format asked for is copied, not converted`() {
        assertEquals(ConvertPlan.AsIs, DownloadFormats.plan(DownloadFormat.MP3, DownloadBitrate.AUTO, SourceCodec.MP3, 256_000L))
        assertEquals(ConvertPlan.AsIs, DownloadFormats.plan(DownloadFormat.FLAC, DownloadBitrate.AUTO, SourceCodec.FLAC, 900_000L))
        assertEquals(ConvertPlan.AsIs, DownloadFormats.plan(DownloadFormat.WAV, DownloadBitrate.AUTO, SourceCodec.WAV, 1_411_000L))
        // And a lossy file made lossless is allowed: a bigger file of the very same sound.
        assertEquals(ConvertPlan.Encode(DownloadFormat.FLAC, null), DownloadFormats.plan(DownloadFormat.FLAC, DownloadBitrate.AUTO, SourceCodec.MP3, 256_000L))
    }

    @Test
    fun `the source of a file is told from its extension`() {
        assertEquals(SourceCodec.MP3, DownloadFormats.sourceOfExtension("mp3"))
        assertEquals(SourceCodec.FLAC, DownloadFormats.sourceOfExtension("FLAC"))
        assertEquals(SourceCodec.WAV, DownloadFormats.sourceOfExtension("wav"))
        assertEquals(SourceCodec.OPUS, DownloadFormats.sourceOfExtension("opus"))
        assertEquals(SourceCodec.AAC, DownloadFormats.sourceOfExtension("m4a"))
        // A .mka is the raw stream, Opus or AAC: only its bytes say which.
        assertNull(DownloadFormats.sourceOfExtension("mka"))
        assertNull(DownloadFormats.sourceOfExtension("txt"))
    }
}
