/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.util.Log
import com.dd3boh.outertune.fingerprint.DecodedSignature
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Asks Shazam what a signature is.
 *
 * HttpURLConnection rather than the Ktor client the rest of the app uses, because this exact
 * request, headers and all, is the one the `:fingerprint` live probe was verified against, and the
 * endpoint is undocumented enough that reproducing it rather than reimplementing it is the cheaper
 * kind of certainty. There is nothing here worth a connection pool: one request per recognition,
 * seconds apart at the very most.
 */
object ShazamClient {

    private const val TAG = "ShazamClient"

    private const val ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A YouTube id wherever it appears in the response.
     *
     * Deliberately not a path into the document. Shazam moves this between `hub.options`,
     * `sections` and a bare `youtubeurl` depending on the track and the day, and a path that is
     * right for one response silently returns nothing for the next. The id is eleven characters of
     * a fixed alphabet and appears in no other field, so finding it is safe and outlasts the shape.
     */
    private val YOUTUBE_ID = Regex("""youtu(?:\.be/|be\.com/watch\?v=)([A-Za-z0-9_-]{11})""")

    suspend fun identify(signature: DecodedSignature, sampleCount: Int): RecognitionResult =
        withContext(Dispatchers.IO) {
            val seconds = sampleCount.toLong() * 1000 / SIGNATURE_SAMPLE_RATE_HZ
            val timestamp = System.currentTimeMillis() / 1000

            // No geolocation worth the name. Shazam wants the field, and sending where the user
            // actually is would be handing a third party a location for a feature that does not
            // need one, so it gets a fixed point and the timezone the format asks for.
            val body = """
                {"geolocation":{"altitude":150.0,"latitude":45.0,"longitude":2.0},
                 "signature":{"samplems":$seconds,
                              "timestamp":$timestamp,
                              "uri":"${signature.encodeToUri()}"},
                 "timestamp":$timestamp,"timezone":"Europe/Paris"}
            """.trimIndent().replace("\n", "")

            val url = ENDPOINT +
                    "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
                    "?sync=true&webv3=true&sampling=true&connected=" +
                    "&shazamapiversion=v3&sharehub=true&video=v3"

            val text = try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Language", "en_US")
                conn.setRequestProperty(
                    "User-Agent",
                    "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)",
                )
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()

                if (code !in 200..299) {
                    Log.w(TAG, "HTTP $code from Shazam")
                    return@withContext RecognitionResult.Failed("HTTP $code")
                }
                payload
            } catch (e: Exception) {
                Log.w(TAG, "Recognition request failed", e)
                return@withContext RecognitionResult.Failed(e.message ?: e.javaClass.simpleName)
            }

            parse(text)
        }

    internal fun parse(payload: String): RecognitionResult {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return RecognitionResult.Failed("Unreadable reply")

        // No track means Shazam heard it and did not know it, which is an answer rather than a
        // failure.
        val track = root["track"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return RecognitionResult.NoMatch

        val title = track.string("title") ?: return RecognitionResult.NoMatch

        return RecognitionResult.Match(
            title = title,
            artist = track.string("subtitle").orEmpty(),
            artworkUrl = track["images"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?.let { it.string("coverarthq") ?: it.string("coverart") },
            youtubeId = YOUTUBE_ID.find(payload)?.groupValues?.get(1),
            isrc = track.string("isrc"),
            key = track.string("key"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }
}
