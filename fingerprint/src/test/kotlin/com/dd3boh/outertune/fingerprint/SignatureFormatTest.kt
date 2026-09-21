/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.fingerprint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Validates the container against a known good signature, independently of whether our peak finding
 * is correct yet.
 *
 * The golden signature is the one AudileTeam/Audile checks in for `ss-s16le-1c-16khz.wav`, produced
 * by the Rust `songrecfp` crate, itself a pruned fork of SongRec. If we can decode it and re-encode
 * it to the same bytes, every field, tag, delta and pad in our writer is right, and any later
 * mismatch is the algorithm's fault rather than the format's.
 */
class SignatureFormatTest {

    private fun golden(): String =
        javaClass.classLoader
            .getResourceAsStream("ss-s16le-1c-16khz.golden.txt")!!
            .readBytes()
            .decodeToString()
            .trim()

    @Test
    fun `golden signature round trips byte for byte`() {
        val uri = golden()
        val original = Base64.getDecoder().decode(uri.removePrefix(DATA_URI_PREFIX))

        val decoded = DecodedSignature.decodeFromBinary(original)
        val reencoded = decoded.encodeToBinary()

        assertArrayEquals(
            "container re-encode differs from the reference bytes",
            original,
            reencoded
        )
        assertEquals("uri form differs", uri, decoded.encodeToUri())
    }

    @Test
    fun `golden signature header matches the documented contract`() {
        val decoded = DecodedSignature.decodeFromUri(golden())

        assertEquals("sample rate", 16_000, decoded.sampleRateHz)
        // The source wav is 10.0s / 160000 samples, but the reference centres it in a 12.0s window,
        // zero padding the remainder. That padding is why this is 192000 and not 160000.
        assertEquals("fingerprint window is 12.0s", 192_000, decoded.numberSamples)

        val total = decoded.peaksByBand.sumOf { it.size }
        assertTrue("expected a non-trivial number of peaks, got $total", total > 100)

        decoded.peaksByBand.forEach { band ->
            band.zipWithNext { a, b ->
                assertTrue(
                    "peaks must be non-decreasing in fft pass within a band",
                    b.fftPassNumber >= a.fftPassNumber
                )
            }
            band.forEach {
                assertTrue("magnitude fits u16", it.peakMagnitude in 0..0xffff)
                assertTrue("bin fits u16", it.correctedPeakFrequencyBin in 0..0xffff)
            }
        }
    }
}
