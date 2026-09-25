/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import com.dd3boh.outertune.fingerprint.SignatureGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Plays an audio file through the Keep listening checks, window by window, as the microphone
 * would hear it: twelve second windows back to back, fingerprinted by the app's own generator,
 * named by Shazam, and fed to [MixWatch] and [CutWatch].
 *
 * For the other half of testing a mashup detector: songs that are not mashups. A radio edit, a
 * song with a chorus that repeats, one whose version Shazam knows differs from the one playing.
 * Each should come through without a single verdict. The audio is decoded by ffmpeg on this
 * machine; only fingerprints go to Shazam, as from the phone.
 *
 * Skipped unless REPLAY_PROBE=1, so an ordinary run never touches the network or the disk.
 *
 *     REPLAY_PROBE=1 REPLAY_FILES="/path/a.mp3;;/path/b.mp3" ./gradlew :app:testCoreDebugUnitTest --tests "*ReplayProbe*" -i
 *
 * REPLAY_SECONDS changes the window length (8, 12, 16 or 20 in the app).
 *
 * REPLAY_SPLICE builds a stream out of pieces of files, "path@from-to" in seconds joined by ";;",
 * and several streams joined by "||",
 * for a mashup whose answer is known: sections of two songs cut back and forth, or one song's
 * sections out of order, or two whole songs one after the other.
 */
class ReplayProbe {

    @Test
    fun replay() {
        assumeTrue(System.getenv("REPLAY_PROBE") == "1")
        val files = System.getenv("REPLAY_FILES")?.split(";;")?.filter { it.isNotBlank() }.orEmpty()
        val seconds = System.getenv("REPLAY_SECONDS")?.toIntOrNull() ?: 12
        for (path in files) File(path).let { replay(it.name, decode(it), seconds) }
        // Several streams, one after the other, separated by "||".
        System.getenv("REPLAY_SPLICE")?.split("||")?.filter { it.isNotBlank() }?.forEach { splice ->
            val pieces = splice.split(";;").filter { it.isNotBlank() }.map { piece ->
                val path = piece.substringBeforeLast("@")
                val (from, to) = piece.substringAfterLast("@").split("-").map { it.toDouble() }
                val all = decode(File(path))
                all.copyOfRange((from * SIGNATURE_SAMPLE_RATE_HZ).toInt(), minOf(all.size, (to * SIGNATURE_SAMPLE_RATE_HZ).toInt()))
            }
            val stream = ShortArray(pieces.sumOf { it.size })
            var at = 0
            pieces.forEach { it.copyInto(stream, at); at += it.size }
            replay("splice of ${pieces.size}", stream, seconds)
        }
    }

    private fun replay(name: String, samples: ShortArray, seconds: Int) {
        val lengthS = samples.size / SIGNATURE_SAMPLE_RATE_HZ
        println("REPLAY ===== $name (${lengthS}s) =====")
        val mixWatch = MixWatch()
        val cutWatch = CutWatch()
        val versionWatch = VersionWatch()
        val window = seconds * SIGNATURE_SAMPLE_RATE_HZ
        var start = 0
        var verdicts = 0
        while (start + window <= samples.size) {
            val atMs = start * 1000L / SIGNATURE_SAMPLE_RATE_HZ
            val part = samples.copyOfRange(start, start + window)
            val match = identify(part)
            val line = StringBuilder("REPLAY %6.1f  ".format(atMs / 1000.0))
            if (match == null) {
                line.append("-- no match --")
            } else {
                line.append("%-34s %7.1f  skew %+.4f".format(match.title, match.offset, match.skew))
                val sighting = MixWatch.Sighting(match.key, match.title, match.artist, atMs, match.offset, match.skew)
                mixWatch.observe(sighting)?.let {
                    verdicts++
                    line.append("  MIX strong=${it.strong} pieces=${it.pieces.joinToString { p -> p.title }}")
                }
                val cut = cutWatch.observe(match.key, match.offset, match.skew, atMs, lengthS)
                // As the engine does: a cut-up song under another that plays straight is part of it.
                val host = mixWatch.steadyHost(atMs)
                if (cut == CutWatch.Verdict.FIRST && host != null && host != match.key) {
                    line.append("  (cut, but under a steady song)")
                } else if (cut != CutWatch.Verdict.NONE) {
                    verdicts++
                    line.append("  CUT $cut")
                }
                versionWatch.observe(sighting)?.let { versions ->
                    if (versions.none { it.key == host }) {
                        verdicts++
                        line.append("  VERSIONS ${versions.joinToString { it.title }}")
                    }
                }
                if (versionWatch.rivalled(match.key)) line.append("  (rivalled)")
            }
            println(line)
            start += window
            // About the phone's own pace. Faster than this, Shazam answers 429 after a dozen.
            Thread.sleep(9_000)
        }
        println("REPLAY ----- $name: $verdicts verdicts -----")
    }

    private data class Match(val key: String, val title: String, val artist: String?, val offset: Double, val skew: Double)

    private fun decode(file: File): ShortArray {
        val process = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", file.absolutePath,
            "-ac", "1", "-ar", SIGNATURE_SAMPLE_RATE_HZ.toString(), "-f", "s16le", "-",
        ).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.getShort() }
    }

    /** The request ShazamClient makes, from a desktop, tried again after a while when throttled. */
    private fun identify(samples: ShortArray): Match? {
        repeat(3) {
            val (throttled, match) = identifyOnce(samples)
            if (!throttled) return match
            Thread.sleep(30_000)
        }
        return null
    }

    private fun identifyOnce(samples: ShortArray): Pair<Boolean, Match?> {
        val signature = SignatureGenerator.makeSignature(samples)
        val now = System.currentTimeMillis() / 1000
        val body = """{"geolocation":{"altitude":150.0,"latitude":45.0,"longitude":2.0},""" +
                """"signature":{"samplems":${samples.size * 1000L / SIGNATURE_SAMPLE_RATE_HZ},""" +
                """"timestamp":$now,"uri":"${signature.encodeToUri()}"},""" +
                """"timestamp":$now,"timezone":"Europe/Paris"}"""
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
                UUID.randomUUID().toString().uppercase() + "/" + UUID.randomUUID() +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Language", "en_US")
            setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        if (connection.responseCode >= 300) {
            println("REPLAY        HTTP ${connection.responseCode}")
            return (connection.responseCode == 429) to null
        }
        val root = Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
        val track = root["track"] as? JsonObject ?: return false to null
        val key = track["key"]?.jsonPrimitive?.content ?: return false to null
        val match = (root["matches"]?.jsonArray?.firstOrNull() as? JsonObject)
        return false to Match(
            key = key,
            title = track["title"]?.jsonPrimitive?.content.orEmpty(),
            artist = track["subtitle"]?.jsonPrimitive?.content,
            offset = match?.get("offset")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            skew = match?.get("timeskew")?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
}
