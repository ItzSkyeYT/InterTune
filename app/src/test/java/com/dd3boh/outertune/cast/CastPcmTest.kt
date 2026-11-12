/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which raw audio goes to which renderer, how long it is, and where each byte of it falls. */
class CastPcmTest {

    // What a renderer lists

    @Test
    fun `an L16 entry is read with its rate, its channels and its profile`() {
        assertEquals(
            PcmOffer("audio/L16;rate=44100;channels=2", 44_100, 2, "LPCM"),
            PcmFormats.offer("audio/L16;rate=44100;channels=2", "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01"),
        )
        // Spaces, capitals and a missing parameter, as firmware writes them.
        assertEquals(PcmOffer("AUDIO/l16; rate=48000", 48_000, null, null), PcmFormats.offer(" AUDIO/l16; rate=48000 ", "*"))
        assertEquals(PcmOffer("audio/L16", null, null, null), PcmFormats.offer("audio/L16"))
    }

    @Test
    fun `L8, L24 and every other type are not raw audio that is sent, nor is nonsense`() {
        assertNull(PcmFormats.offer("audio/L8;rate=44100;channels=2"))
        assertNull(PcmFormats.offer("audio/L24;rate=96000;channels=2"))
        assertNull(PcmFormats.offer("audio/wav"))
        assertNull(PcmFormats.offer("audio/mpeg"))
        assertNull(PcmFormats.offer("audio/L16;rate=abc;channels=2"))
        assertNull(PcmFormats.offer("audio/L16;rate=1000;channels=2"))
        assertNull(PcmFormats.offer("audio/L16;rate=44100;channels=0"))
    }

    @Test
    fun `a renderer that lists only L8 takes nothing that is sent`() {
        val zeppelinOfL8 = ProtocolInfo.support("http-get:*:audio/L8;rate=44100;channels=2:*")
        assertNull(PcmFormats.target(zeppelinOfL8))
        assertNull(PcmFormats.target(FormatSupport.Unknown))
        assertNull(PcmFormats.target(FormatSupport.Listed(setOf("audio/mpeg"))))
    }

    @Test
    fun `audio L16 that names no rate is sent at CD quality, which every such renderer plays`() {
        // As the message of 4 Nov 2026 showed it: "it lists audio/l16, audio/l8".
        val target = PcmFormats.target(FormatSupport.Listed(setOf("audio/l16", "audio/l8")))!!
        assertEquals(PcmTarget(listOf(PcmOffer("audio/L16"))), target)
        assertEquals(PcmForm(PcmContainer.L16, 44_100, 2), PcmFormats.pick(target, 48_000, 2))
        assertEquals(PcmForm(PcmContainer.L16, 44_100, 2), PcmFormats.pick(target, 44_100, 2))
        assertEquals(PcmForm(PcmContainer.L16, 44_100, 2), PcmFormats.pick(PcmTarget(listOf(PcmFormats.offer("audio/L16", "*")!!)), 96_000, 2))
    }

    // The rate and the channels

    private fun l16(vararg entries: String) = PcmTarget(entries.map { PcmFormats.offer(it, "DLNA.ORG_PN=LPCM")!! })

    @Test
    fun `the song's own rate goes when the renderer lists it, so nothing is resampled`() {
        val both = l16("audio/L16;rate=44100;channels=2", "audio/L16;rate=48000;channels=2")
        // Opus decodes at 48 kHz, YouTube's AAC at 44.1.
        assertEquals(48_000, PcmFormats.pick(both, 48_000, 2).rate)
        assertEquals(44_100, PcmFormats.pick(both, 44_100, 2).rate)
    }

    @Test
    fun `a rate it does not list is the nearest of CD quality or better, else the highest it lists`() {
        assertEquals(44_100, PcmFormats.pick(l16("audio/L16;rate=44100;channels=2"), 48_000, 2).rate)
        assertEquals(44_100, PcmFormats.pick(l16("audio/L16;rate=96000;channels=2", "audio/L16;rate=44100;channels=2"), 48_000, 2).rate)
        assertEquals(48_000, PcmFormats.pick(l16("audio/L16;rate=48000;channels=2", "audio/L16;rate=32000;channels=2"), 44_100, 2).rate)
        assertEquals(32_000, PcmFormats.pick(l16("audio/L16;rate=22050;channels=2", "audio/L16;rate=32000;channels=2"), 48_000, 2).rate)
        // A file of an odd rate on the phone goes up to CD quality rather than down.
        assertEquals(44_100, PcmFormats.pick(PcmTarget.LPCM, 22_050, 2).rate)
        // A hi-res file to a renderer that takes its rate stays as it is.
        assertEquals(96_000, PcmFormats.pick(l16("audio/L16;rate=96000;channels=2", "audio/L16;rate=48000;channels=2"), 96_000, 2).rate)
    }

    @Test
    fun `stereo where it is listed, mono otherwise, whatever the song has`() {
        assertEquals(2, PcmFormats.pick(l16("audio/L16;rate=44100;channels=1", "audio/L16;rate=44100;channels=2"), 44_100, 1).channels)
        assertEquals(1, PcmFormats.pick(l16("audio/L16;rate=44100;channels=1"), 44_100, 2).channels)
        // The channels at the rate picked, not anywhere in the list.
        val form = PcmFormats.pick(l16("audio/L16;rate=44100;channels=1", "audio/L16;rate=32000;channels=2"), 48_000, 2)
        assertEquals(44_100 to 1, form.rate to form.channels)
    }

    @Test
    fun `the type is the renderer's own words when they name exactly the form`() {
        val listed = PcmTarget(listOf(PcmFormats.offer("audio/L16;rate=44100;channels=2;x=1")!!))
        assertEquals("audio/L16;rate=44100;channels=2;x=1", PcmFormats.pick(listed, 48_000, 2).contentType)
        // An entry naming no channels is not the exact form: RFC 2586's own spelling then.
        val loose = PcmTarget(listOf(PcmFormats.offer("AUDIO/L16;RATE=44100")!!))
        assertEquals("audio/L16;rate=44100;channels=2", PcmFormats.pick(loose, 48_000, 2).contentType)
    }

    @Test
    fun `WAV goes only where L16 is not listed, at 44 1 or 48 kHz`() {
        val wavOnly = PcmFormats.target(FormatSupport.Listed(setOf("audio/mpeg", "audio/x-wav")))!!
        assertEquals(PcmTarget(emptyList(), "audio/x-wav"), wavOnly)
        assertEquals(PcmForm(PcmContainer.WAV, 48_000, 2, "audio/x-wav"), PcmFormats.pick(wavOnly, 48_000, 2))
        assertEquals(PcmForm(PcmContainer.WAV, 44_100, 2, "audio/x-wav"), PcmFormats.pick(wavOnly, 44_100, 2))
        assertEquals(44_100, PcmFormats.pick(wavOnly, 22_050, 1).rate)
        assertEquals(48_000, PcmFormats.pick(wavOnly, 32_000, 1).rate)
        assertEquals(1, PcmFormats.pick(wavOnly, 22_050, 1).channels)
        // Both listed: L16, the format every renderer must take.
        val both = PcmFormats.target(ProtocolInfo.support("http-get:*:audio/wav:*,http-get:*:audio/L16;rate=44100;channels=2:*"))!!
        assertEquals(PcmContainer.L16, PcmFormats.pick(both, 48_000, 2).container)
    }

    // Length and place

    @Test
    fun `the length is the duration's frames, four bytes each in 16-bit stereo`() {
        val cd = PcmForm(PcmContainer.L16, 44_100, 2)
        val frames = PcmFormats.frames(213_500_000, 44_100)
        assertEquals(9_415_350L, frames)
        assertEquals(37_661_400L, cd.length(frames))
        assertEquals(176_400, cd.bytesPerSecond)
        assertEquals(40_992_000L, PcmForm(PcmContainer.L16, 48_000, 2).length(PcmFormats.frames(213_500_000, 48_000)))
        // Rounded to the nearest frame, not cut.
        assertEquals(44_101L, PcmFormats.frames(1_000_012, 44_100))
        assertEquals(-1L, PcmFormats.frames(-1, 44_100))
        assertEquals(-1L, cd.length(-1))
        // WAV is the same with its 44-byte header before.
        assertEquals(37_661_444L, PcmForm(PcmContainer.WAV, 44_100, 2).length(frames))
    }

    @Test
    fun `a byte is a frame, and a place inside that frame`() {
        val cd = PcmForm(PcmContainer.L16, 44_100, 2)
        assertEquals(0L to 0, cd.frameAt(0) to cd.offsetInFrame(0))
        assertEquals(1L to 1, cd.frameAt(5) to cd.offsetInFrame(5))
        assertEquals(44_100L to 3, cd.frameAt(176_403) to cd.offsetInFrame(176_403))
        assertEquals(1_000_000L, cd.timeUs(44_100))
        val wav = PcmForm(PcmContainer.WAV, 48_000, 1)
        assertEquals(0L to 0, wav.frameAt(10) to wav.offsetInFrame(10))
        assertEquals(0L to 0, wav.frameAt(44) to wav.offsetInFrame(44))
        assertEquals(1L to 1, wav.frameAt(47) to wav.offsetInFrame(47))
    }

    @Test
    fun `a WAV header says RIFF, PCM, the rate, the channels and the data's size`() {
        val form = PcmForm(PcmContainer.WAV, 48_000, 2)
        val header = form.wavHeader(48_000)
        fun u32(at: Int) = (0..3).sumOf { (header[at + it].toLong() and 0xFF) shl (8 * it) }
        fun u16(at: Int) = (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)
        fun text(at: Int) = String(header, at, 4, Charsets.US_ASCII)
        assertEquals(44, header.size)
        assertEquals(listOf("RIFF", "WAVE", "fmt ", "data"), listOf(text(0), text(8), text(12), text(36)))
        assertEquals(36L + 192_000, u32(4))
        assertEquals(16L, u32(16))
        assertEquals(1, u16(20))
        assertEquals(2, u16(22))
        assertEquals(48_000L, u32(24))
        assertEquals(192_000L, u32(28))
        assertEquals(4, u16(32))
        assertEquals(16, u16(34))
        assertEquals(192_000L, u32(40))
        // A size not known is written as the largest there is, as a stream is.
        assertEquals(0xFFFFFFFFL, PcmForm(PcmContainer.WAV, 48_000, 2).wavHeader(-1).let { h -> (0..3).sumOf { (h[40 + it].toLong() and 0xFF) shl (8 * it) } })
    }

    @Test
    fun `a form is named in its url, and nothing else is taken for one`() {
        for (form in listOf(PcmForm(PcmContainer.L16, 44_100, 2), PcmForm(PcmContainer.WAV, 48_000, 1))) {
            assertEquals(form, PcmForm.fromKey(form.key))
        }
        assertEquals("l16-44100-2", PcmForm(PcmContainer.L16, 44_100, 2).key)
        for (key in listOf("l8-44100-2", "L16-44100-2", "l16-44100-3", "l16-44100", "l16-1000-2", "l16-+44100-2", "l16-44100-2-x", "s", "aac", "")) {
            assertNull(key, PcmForm.fromKey(key))
        }
    }

    @Test
    fun `LPCM is the profile of 16-bit audio at 44 1 and 48 kHz in one or two channels`() {
        assertEquals("LPCM", PcmFormats.profile("audio/L16;rate=44100;channels=2"))
        assertEquals("LPCM", PcmFormats.profile("audio/L16;rate=48000;channels=1"))
        assertNull(PcmFormats.profile("audio/L16;rate=32000;channels=2"))
        assertNull(PcmFormats.profile("audio/L16;rate=44100;channels=6"))
        assertNull(PcmFormats.profile("audio/wav"))
        assertNull(PcmFormats.profile("audio/mp4"))
    }

    @Test
    fun `the form's bytes per frame and order are as RFC 2586 and WAV have them`() {
        assertArrayEquals(intArrayOf(4, 2), intArrayOf(PcmForm(PcmContainer.L16, 44_100, 2).frameBytes, PcmForm(PcmContainer.L16, 44_100, 1).frameBytes))
        assertEquals(true, PcmForm(PcmContainer.L16, 44_100, 2).bigEndian)
        assertEquals(false, PcmForm(PcmContainer.WAV, 44_100, 2).bigEndian)
        assertEquals("pcm", PcmForm(PcmContainer.L16, 44_100, 2).extension)
        assertEquals("wav", PcmForm(PcmContainer.WAV, 44_100, 2).extension)
    }
}
