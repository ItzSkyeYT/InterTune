/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.fingerprint

import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

/**
 * The only test that can say the fingerprinter actually works.
 *
 * Everything in [SignatureGeneratorTest] checks the generator against itself and against the
 * reference's constants. None of it can tell you whether Shazam accepts the result, because the
 * reference ships no golden audio-to-signature vector and one cut from a recording could not be
 * committed here anyway. So this sends a real clip to the real endpoint.
 *
 * Skipped unless CLIP names a raw file of mono, signed 16-bit, 16000 Hz samples, so an ordinary
 * test run and CI never touch the network. To make one:
 *
 *     ffmpeg -f pulse -i <monitor source> -t 14 -ac 1 -ar 16000 -f s16le clip.raw
 *     CLIP=/path/to/clip.raw ./gradlew :fingerprint:test --tests '*LiveRecognitionProbe*' -i
 *
 * First run, 8 Sep: 14 s captured off an emulator playing The Weeknd's Blinding Lights, at about
 * -37 dBFS because the emulator's output is quiet. 920 peaks, HTTP 200, matched by title and
 * artist. It also matched after normalising to -4 dBFS with an identical peak count, which is
 * worth knowing: peak selection is almost entirely relative comparisons between neighbouring bins,
 * so it is level independent until the absolute 1/64 gate starts biting.
 */
class LiveRecognitionProbe {

    private fun readClip(path: String): ShortArray {
        val bytes = File(path).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buf.getShort() }
    }

    /** The emulator's output is about -37 dBFS; a real mic at sane gain is not. */
    private fun normalise(samples: ShortArray, targetPeak: Int = 20000): ShortArray {
        val peak = samples.maxOf { abs(it.toInt()) }
        if (peak == 0) return samples
        val gain = targetPeak.toDouble() / peak
        println("normalising: peak $peak, gain ${"%.1f".format(gain)}x")
        return ShortArray(samples.size) {
            (samples[it] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun askShazam() {
        val path = System.getenv("CLIP")
        assumeNotNull(path)

        val raw = readClip(path)
        println("clip: ${raw.size} samples = ${"%.1f".format(raw.size / 16000.0)}s")

        for ((label, samples) in listOf("as recorded" to raw, "normalised" to normalise(raw))) {
            val signature = SignatureGenerator.makeSignature(samples)
            val peaks = signature.peaksByBand.sumOf { it.size }
            println("\n--- $label: $peaks peaks (${signature.peaksByBand.map { it.size }}) ---")
            if (peaks == 0) {
                println("no peaks, nothing to ask")
                continue
            }
            println(query(signature, samples.size))
        }
    }

    private fun query(signature: DecodedSignature, sampleCount: Int): String {
        val timestamp = System.currentTimeMillis() / 1000
        val body = """
            {"geolocation":{"altitude":150.0,"latitude":45.0,"longitude":2.0},
             "signature":{"samplems":${sampleCount * 1000L / SIGNATURE_SAMPLE_RATE_HZ},
                          "timestamp":$timestamp,
                          "uri":"${signature.encodeToUri()}"},
             "timestamp":$timestamp,"timezone":"Europe/Paris"}
        """.trimIndent().replace("\n", "")

        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
                "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"

        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Content-Language", "en_US")
        conn.setRequestProperty(
            "User-Agent",
            "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)",
        )
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return "HTTP $code\n" + summarise(text)
    }

    /** Just enough parsing to see whether it matched, without a json dependency. */
    private fun summarise(json: String): String {
        fun field(name: String): String? =
            Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

        val title = field("title")
        val subtitle = field("subtitle")
        return if (title != null) {
            "MATCH: $title - $subtitle"
        } else {
            "no match. first 400 chars:\n" + json.take(400)
        }
    }
}
