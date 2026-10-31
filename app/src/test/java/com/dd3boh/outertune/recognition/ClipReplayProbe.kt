/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import com.dd3boh.outertune.fingerprint.SignatureGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
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
 * Plays a recording through one listen of the phone's own sound ([ClipSession]) the way the engine
 * does it: a second of sound at a time, windows asked about the way [ClipListen] says, speeds tried,
 * the windows weighed by [ClipEvidence]. Shazam is the real one, asked the way ShazamClient asks; the
 * audio is decoded by ffmpeg on this machine and only fingerprints go out, as from the phone.
 *
 * For a reel a listen was wrong about (the organ playing a dance tune of 31 Oct 2026, named
 * "Sunshine" by the first twelve seconds): what the listen logs is what the engine would, and the
 * last line says how it ended.
 *
 * Skipped unless REPLAY_PROBE=1, so an ordinary run never touches the network or the disk.
 *
 *     REPLAY_PROBE=1 REPLAY_FILES="/path/reel.mp4;;/path/b.mp3" ./gradlew :app:testCoreDebugUnitTest --tests "*ClipReplayProbe*" -i
 *
 * REPLAY_FROM starts the recording that many seconds in, REPLAY_SECONDS stops it after that many of
 * sound (default 60, what a listen hears at most), REPLAY_SLEEP is the seconds waited after each
 * request (default 6; Shazam refuses after about a dozen in quick succession, then waits 30 s).
 */
class ClipReplayProbe {

    @Test
    fun replay() {
        assumeTrue(System.getenv("REPLAY_PROBE") == "1")
        val files = System.getenv("REPLAY_FILES")?.split(";;")?.filter { it.isNotBlank() }.orEmpty()
        val from = System.getenv("REPLAY_FROM")?.toIntOrNull() ?: 0
        val seconds = System.getenv("REPLAY_SECONDS")?.toIntOrNull() ?: ClipListen.MOST_HEARD_SECONDS
        for (path in files) listen(File(path), from, seconds)
    }

    private fun listen(file: File, from: Int, seconds: Int) {
        val all = decode(file)
        val rate = SIGNATURE_SAMPLE_RATE_HZ
        val sound = all.copyOfRange(minOf(all.size, from * rate), minOf(all.size, (from + seconds) * rate))
        println("CLIP ===== ${file.name} (${all.size / rate} s, from $from s, ${sound.size / rate} s of it) =====")
        var second = 0
        val session = ClipSession(ask = { identify(it) }, log = { println("CLIP   %3d s  %s".format(second, it)) })
        var verdict: ClipSession.Verdict? = null
        runBlocking {
            for (at in sound.indices step rate) {
                if (verdict != null || at + rate > sound.size) break
                second = at / rate + 1
                verdict = session.hear(sound.copyOfRange(at, at + rate), at * 1000L / rate)
            }
        }
        val ended = verdict ?: session.end("the recording ended")
        println(
            "CLIP ----- ${file.name}: " + when (ended) {
                is ClipSession.Verdict.Answer ->
                    "'${ended.track.title}' by '${ended.track.artist}' at ${ended.track.heardAtRate}x after $second s: ${ended.why}"
                is ClipSession.Verdict.NoSong ->
                    "no song after $second s (silent=${ended.silent}, refused=${ended.refused}): ${ended.why}"
            } + ", ${session.requests} requests -----"
        )
    }

    private fun decode(file: File): ShortArray {
        val process = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", file.absolutePath,
            "-vn", "-ac", "1", "-ar", SIGNATURE_SAMPLE_RATE_HZ.toString(), "-f", "s16le", "-",
        ).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.getShort() }
    }

    /** What ShazamClient.identify answers for [samples], from a desktop: a throttled request waits and is asked again. */
    private fun identify(samples: ShortArray): RecognitionOutcome {
        val signature = SignatureGenerator.makeSignature(samples)
        if (signature.peaksByBand.sumOf { it.size } < 30) return RecognitionOutcome.Failed("silence")
        val sleep = (System.getenv("REPLAY_SLEEP")?.toLongOrNull() ?: 6L) * 1000
        repeat(3) {
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
            val code = connection.responseCode
            if (code in 200..299) {
                val answer = parse(connection.inputStream.bufferedReader().readText())
                Thread.sleep(sleep)
                return answer
            }
            println("CLIP        HTTP $code")
            Thread.sleep(if (code == 429) 30_000 else 5_000)
        }
        return RecognitionOutcome.Failed("http 429")
    }

    private fun parse(text: String): RecognitionOutcome {
        val root = Json.parseToJsonElement(text).jsonObject
        val track = root["track"] as? JsonObject ?: return RecognitionOutcome.NoMatch
        val title = track["title"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return RecognitionOutcome.NoMatch
        val match = (root["matches"] as? JsonArray)?.firstOrNull() as? JsonObject
        return RecognitionOutcome.Match(
            Recognised(
                title = title,
                artist = track["subtitle"]?.jsonPrimitive?.content?.ifEmpty { null },
                artworkUrl = null,
                isrc = track["isrc"]?.jsonPrimitive?.content,
                shazamKey = track["key"]?.jsonPrimitive?.content,
                offsetSeconds = match?.get("offset")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                timeSkew = match?.get("timeskew")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                frequencySkew = match?.get("frequencyskew")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        )
    }
}
