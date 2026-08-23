/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Port of the Shazam signature container from SongRec by marin-m
 * (https://github.com/marin-m/SongRec, GPL-3.0, tag 0.7.3, commit
 * 5afbf7361fcd72a3edaaeed24dc0ea150fd79385), by way of the pruned `songrecfp` crate in
 * AudileTeam/Audile by Aleksey Saenko (GPL-3.0). Reimplemented in Kotlin; the wire format is
 * unchanged.
 */

package com.dd3boh.outertune.fingerprint

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32

const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

/** Header is fixed length; peak chunks follow the 8 byte block after it. */
private const val HEADER_SIZE = 48
private const val MAGIC1 = 0xcafe2580.toInt()
private const val MAGIC2 = 0x94119c00.toInt()
private const val FIXED_VALUE = (15 shl 19) + 0x40000 // 0x7c0000
private const val PEAK_CHUNK_TAG = 0x60030040
private const val SECOND_BLOCK_MARKER = 0x40000000

/** A peak in one of the four bands. Bin and magnitude are stored as unsigned 16 bit. */
data class FrequencyPeak(
    val fftPassNumber: Int,
    val peakMagnitude: Int,
    val correctedPeakFrequencyBin: Int,
)

/**
 * Sample rates Shazam's container can name. The id is what goes in the header, shifted left by 27.
 */
enum class SampleRate(val hz: Int, val id: Int) {
    HZ_8000(8000, 1),
    HZ_11025(11025, 2),
    HZ_16000(16000, 3),
    HZ_32000(32000, 4),
    HZ_44100(44100, 5),
    HZ_48000(48000, 6);

    companion object {
        fun ofHz(hz: Int): SampleRate =
            entries.firstOrNull { it.hz == hz }
                ?: throw IllegalArgumentException("Unsupported sample rate for a Shazam packet: $hz")

        fun ofId(id: Int): SampleRate =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown sample rate id in signature header: $id")
    }
}

/**
 * A decoded signature: the peaks, bucketed into the four bands, plus enough header state to
 * re-encode byte for byte.
 */
data class DecodedSignature(
    val sampleRateHz: Int,
    val numberSamples: Int,
    /** Indexed by band, 0..3. */
    val peaksByBand: List<List<FrequencyPeak>>,
) {
    init {
        require(peaksByBand.size == 4) { "expected 4 bands, got ${peaksByBand.size}" }
    }

    fun encodeToUri(): String =
        DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encodeToBinary())

    fun encodeToBinary(): ByteArray {
        val peakChunks = ByteBuffer.allocate(MAX_PEAK_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        peaksByBand.forEachIndexed { band, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed

            val body = ByteBuffer.allocate(MAX_PEAK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var fftPassNumber = 0
            for (peak in peaks) {
                require(peak.fftPassNumber >= fftPassNumber) {
                    "peaks must be ordered by fft pass within a band"
                }
                // A gap too large for the single delta byte is escaped with 0xff followed by the
                // absolute pass number. Note the delta byte is still written afterwards, and is
                // zero at that point, which is what the reference does.
                if (peak.fftPassNumber - fftPassNumber >= 255) {
                    body.put(0xff.toByte())
                    body.putInt(peak.fftPassNumber)
                    fftPassNumber = peak.fftPassNumber
                }
                body.put((peak.fftPassNumber - fftPassNumber).toByte())
                body.putShort(peak.peakMagnitude.toShort())
                body.putShort(peak.correctedPeakFrequencyBin.toShort())
                fftPassNumber = peak.fftPassNumber
            }

            val bodyBytes = ByteArray(body.position()).also { body.flip(); body.get(it) }
            peakChunks.putInt(PEAK_CHUNK_TAG + band)
            peakChunks.putInt(bodyBytes.size)
            peakChunks.put(bodyBytes)
            repeat((4 - bodyBytes.size % 4) % 4) { peakChunks.put(0) }
        }

        val chunkBytes = ByteArray(peakChunks.position()).also { peakChunks.flip(); peakChunks.get(it) }
        val total = HEADER_SIZE + 8 + chunkBytes.size
        val sizeMinusHeader = total - HEADER_SIZE

        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC1)
        out.putInt(0)                       // crc32, filled in below
        out.putInt(sizeMinusHeader)
        out.putInt(MAGIC2)
        out.putInt(0); out.putInt(0); out.putInt(0)
        out.putInt(SampleRate.ofHz(sampleRateHz).id shl 27)
        out.putInt(0); out.putInt(0)
        // The reference computes this as an f32 multiply truncated to u32; at 0.24 and these rates
        // the result is exact, but the cast order is preserved deliberately.
        out.putInt(numberSamples + (sampleRateHz * 0.24f).toInt())
        out.putInt(FIXED_VALUE)
        out.putInt(SECOND_BLOCK_MARKER)
        out.putInt(sizeMinusHeader)
        out.put(chunkBytes)

        val bytes = out.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc.toInt())
        return bytes
    }

    companion object {
        /** Generous upper bound; a 12 second signature is a few kB. */
        private const val MAX_PEAK_BYTES = 1 shl 20

        fun decodeFromUri(uri: String): DecodedSignature =
            decodeFromBinary(Base64.getDecoder().decode(uri.removePrefix(DATA_URI_PREFIX)))

        /**
         * Inverse of [encodeToBinary]. Exists so the container can be validated against a known
         * good signature without depending on the peak-finding algorithm being correct yet.
         */
        fun decodeFromBinary(bytes: ByteArray): DecodedSignature {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buf.getInt(0) == MAGIC1) { "bad magic1" }
            require(buf.getInt(12) == MAGIC2) { "bad magic2" }

            val storedCrc = buf.getInt(4).toLong() and 0xffffffffL
            val actualCrc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
            require(storedCrc == actualCrc) { "crc32 mismatch" }

            val sampleRate = SampleRate.ofId(buf.getInt(28) ushr 27)
            val numberSamples = buf.getInt(40) - (sampleRate.hz * 0.24f).toInt()

            val bands = List(4) { mutableListOf<FrequencyPeak>() }
            var pos = HEADER_SIZE + 8
            while (pos + 8 <= bytes.size) {
                val tag = buf.getInt(pos)
                val len = buf.getInt(pos + 4)
                val band = tag - PEAK_CHUNK_TAG
                require(band in 0..3) { "unexpected chunk tag 0x${tag.toString(16)} at $pos" }
                var p = pos + 8
                val end = p + len
                var fftPassNumber = 0
                while (p < end) {
                    val delta = bytes[p].toInt() and 0xff
                    p++
                    if (delta == 0xff) {
                        fftPassNumber = buf.getInt(p)
                        p += 4
                        continue
                    }
                    fftPassNumber += delta
                    val magnitude = buf.getShort(p).toInt() and 0xffff
                    val bin = buf.getShort(p + 2).toInt() and 0xffff
                    p += 4
                    bands[band].add(FrequencyPeak(fftPassNumber, magnitude, bin))
                }
                pos = end + ((4 - len % 4) % 4)
            }
            return DecodedSignature(sampleRate.hz, numberSamples, bands.map { it.toList() })
        }
    }
}
