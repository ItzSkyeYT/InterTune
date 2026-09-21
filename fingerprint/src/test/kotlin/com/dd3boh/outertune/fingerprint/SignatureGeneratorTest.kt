/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The algorithm half of the fingerprinter.
 *
 * There is no golden audio-to-signature vector to check against; the reference ships none, and one
 * taken from a recording could not be committed anyway. So these pin what can be proved without
 * one: that the window in the source is still the reference's to the bit, that peaks land in the
 * band the input occupies, that silence stays silent, and that the container still accepts what
 * the generator emits. The end-to-end proof is a live recognition, which no unit test can be.
 */
class SignatureGeneratorTest {

    /** The reference's own 2048 f32 window, as committed by SongRec and Audile. */
    private fun referenceWindow(): FloatArray {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/hanning2048_reference.f32")) {
            "reference window resource missing"
        }.use { it.readBytes() }
        assertEquals("reference window should be 2048 floats", 2048 * 4, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(2048) { buf.getFloat() }
    }

    /**
     * Something with structure across the whole band, so peaks appear in all four bands rather than
     * only where a single tone sits. Seeded, so a failure is reproducible.
     */
    private fun syntheticAudio(seconds: Double = 4.0): ShortArray {
        val random = Random(20260908)
        val n = (SIGNATURE_SAMPLE_RATE_HZ * seconds).toInt()
        val tones = doubleArrayOf(320.0, 660.0, 1180.0, 2400.0, 4300.0)
        return ShortArray(n) { i ->
            val t = i.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
            // A slow tremolo on each partial so the time-domain neighbour tests have something to
            // reject, rather than a stationary tone that trivially peaks everywhere.
            var v = 0.0
            for ((k, f) in tones.withIndex()) {
                val envelope = 0.55 + 0.45 * sin(2 * PI * (0.7 + 0.3 * k) * t)
                v += envelope * sin(2 * PI * f * t) / tones.size
            }
            v += random.nextDouble(-0.02, 0.02)
            (v * 12000).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * The window in the source is the reference's, to the bit.
     *
     * This started as a check that a computed Hann could stand in for the tabulated one. It cannot:
     * running both through the generator produced signatures differing in a handful of peaks,
     * because the reference's literals are rounded to five significant figures and a computed f32
     * is not. The window was switched to the reference's own numbers and this became the test that
     * they stay that way.
     */
    @Test
    fun theWindowIsTheReferenceTableExactly() {
        val audio = syntheticAudio()

        val shipped = SignatureGenerator.makeSignature(audio)
        val reference = SignatureGenerator.makeSignature(audio, referenceWindow())

        assertTrue("the synthetic input produced no peaks at all", shipped.peaksByBand.any { it.isNotEmpty() })
        assertEquals(
            "the shipped Hanning window no longer matches the reference table",
            reference.encodeToUri(),
            shipped.encodeToUri(),
        )
    }

    /**
     * A tone lands in the band that tone belongs to.
     *
     * Bands are 250-520, 520-1450, 1450-3500 and 3500-5500 Hz. 1 kHz is comfortably inside the
     * second and far from either edge, so this catches the corrected-frequency arithmetic being out
     * by a factor or an offset, which is the failure mode that still produces a plausible looking
     * signature.
     *
     * The tone has to be modulated. A perfectly stationary one yields no peaks at all, and that is
     * the algorithm working: a candidate must be strictly greater than the same bin in fourteen
     * other passes, and a constant tone is equal to itself in all of them. Worth knowing before
     * concluding the generator is broken, which is what a flat sine first suggested here.
     */
    @Test
    fun aToneLandsInItsOwnBand() {
        val n = SIGNATURE_SAMPLE_RATE_HZ * 4
        val audio = ShortArray(n) { i ->
            val t = i.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
            val envelope = 0.55 + 0.45 * sin(2 * PI * 0.9 * t)
            (envelope * sin(2 * PI * 1000.0 * t) * 12000).roundToInt().toShort()
        }

        val signature = SignatureGenerator.makeSignature(audio)
        val counts = signature.peaksByBand.map { it.size }
        println("peaks per band for a 1 kHz tone: $counts")

        assertTrue("no peaks found for a 1 kHz tone", counts.sum() > 0)
        assertEquals(
            "the strongest band should be 520-1450 Hz, got counts $counts",
            1,
            counts.indexOf(counts.max()),
        )
    }

    /** Silence has nothing to peak on, and must not invent anything. */
    @Test
    fun silenceProducesNoPeaks() {
        val signature = SignatureGenerator.makeSignature(ShortArray(SIGNATURE_SAMPLE_RATE_HZ * 2))
        assertEquals(0, signature.peaksByBand.sumOf { it.size })
    }

    /** The header carries the sample count it was given, and the container still round-trips. */
    @Test
    fun signatureReencodesThroughTheContainer() {
        val audio = syntheticAudio(seconds = 3.0)
        val signature = SignatureGenerator.makeSignature(audio)

        assertEquals(SIGNATURE_SAMPLE_RATE_HZ, signature.sampleRateHz)
        assertEquals(audio.size, signature.numberSamples)

        val uri = signature.encodeToUri()
        assertTrue("signature should be a shazam sig data uri", uri.startsWith(DATA_URI_PREFIX))

        val peaks = signature.peaksByBand.sumOf { it.size }
        println("synthetic 3s: $peaks peaks, ${uri.length} chars of data uri")
        assertTrue("expected some peaks from a tonal input", peaks > 0)
    }

    /** Peaks are ordered by pass within a band, which the container encoder requires. */
    @Test
    fun peaksAreOrderedByPassWithinEachBand() {
        val signature = SignatureGenerator.makeSignature(syntheticAudio())
        for ((band, peaks) in signature.peaksByBand.withIndex()) {
            var previous = -1
            for (peak in peaks) {
                assertTrue(
                    "band $band went backwards: ${peak.fftPassNumber} after $previous",
                    peak.fftPassNumber >= previous,
                )
                previous = peak.fftPassNumber
            }
        }
    }
}
