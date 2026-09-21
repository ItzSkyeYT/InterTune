/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.util.Log
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import com.dd3boh.outertune.fingerprint.SignatureGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Shazam said, reduced to the parts this app can act on.
 *
 * Deliberately does not carry a YouTube id, because the response does not contain one. Checked
 * against a real match rather than assumed: the only YouTube reference anywhere in the document is
 * `track.hub.providers[].actions[].uri`, and that is a *search* url of the form
 * `music.youtube.com/search?q=Blinding+Lights+The+Weeknd`. So every recognition has to be resolved
 * by searching, which is exactly why the result is shown for confirmation rather than added
 * silently: a search for a title and an artist can land on a live take, a cover or a sped-up edit.
 *
 * [isrc] identifies the recording rather than the song, so it is the strongest thing here for
 * telling two versions apart. Nothing uses it yet; it is carried because throwing it away and
 * wanting it later would mean another round of recognitions.
 */
data class Recognised(
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val isrc: String?,
    /** Shazam's own id for the track, useful if this ever needs deduplicating. */
    val shazamKey: String?,
    /**
     * Where in the reference recording this sample matched, in seconds.
     *
     * The useful part of a second listen. Two recognitions of the same track, taken a known number
     * of seconds apart, should advance this by that many seconds. If it advances faster the room is
     * playing a sped-up edit, slower and it is a slowed one, and that is measurable rather than
     * guessed from a title.
     */
    val offsetSeconds: Double = 0.0,
    /** Shazam's own estimate of the playback rate deviation, near zero for an unaltered copy. */
    val timeSkew: Double = 0.0,
    /** And of the pitch deviation, which a slowed or nightcore edit moves along with the rate. */
    val frequencySkew: Double = 0.0,
) {
    /** What to hand a YouTube search. */
    val searchQuery: String get() = listOfNotNull(title, artist).joinToString(" ")
}

sealed interface RecognitionOutcome {
    data class Match(val track: Recognised) : RecognitionOutcome
    /** The request worked and Shazam simply did not know it. */
    data object NoMatch : RecognitionOutcome
    data class Failed(val reason: String) : RecognitionOutcome
}

/**
 * Sends a fingerprint to Shazam and reads the answer.
 *
 * The endpoint takes no api key and is not documented anywhere official, so the request shape
 * follows Audile's ShazamRecognitionService exactly, down to the two uuids in the path and the
 * query parameters. Guessing any of it produces a 200 with nothing useful in it.
 */
@Singleton
class ShazamClient @Inject constructor() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun identify(samples: ShortArray): RecognitionOutcome = withContext(Dispatchers.IO) {
        val signature = runCatching { SignatureGenerator.makeSignature(samples) }.getOrElse {
            Log.e(TAG, "Could not fingerprint the recording", it)
            return@withContext RecognitionOutcome.Failed("fingerprint")
        }

        val peaks = signature.peaksByBand.sumOf { it.size }
        if (peaks < MIN_USEFUL_PEAKS) {
            // Silence, or a room too far from the speaker. Worth separating from a genuine
            // no-match, since the fix is "hold it closer" rather than "Shazam does not have it".
            //
            // Not zero. A silent emulator microphone produced exactly one peak over twelve
            // seconds and sailed past a zero check, so the sheet blamed Shazam for a room that had
            // nothing in it. A real match off a speaker gave 920, so anything down here is noise.
            Log.i(TAG, "Only $peaks peaks in the recording, not asking")
            return@withContext RecognitionOutcome.Failed("silence")
        }
        Log.i(TAG, "Fingerprinted ${samples.size} samples into $peaks peaks")

        val timestamp = System.currentTimeMillis() / 1000
        val payload = JSONObject()
            .put(
                "geolocation", JSONObject()
                    .put("altitude", 150.0).put("latitude", 45.0).put("longitude", 2.0)
            )
            .put(
                "signature", JSONObject()
                    .put("samplems", samples.size * 1000L / SIGNATURE_SAMPLE_RATE_HZ)
                    .put("timestamp", timestamp)
                    .put("uri", signature.encodeToUri())
            )
            .put("timestamp", timestamp)
            .put("timezone", java.util.TimeZone.getDefault().id)
            .toString()

        val url = "$ENDPOINT${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3" +
                "&sharehub=true&video=v3"

        val response = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Content-Language", "en_US")
                    .header("User-Agent", USER_AGENT)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.code to it.body?.string().orEmpty() }
        }.getOrElse {
            Log.w(TAG, "Recognition request failed", it)
            return@withContext RecognitionOutcome.Failed("network")
        }

        val (code, text) = response
        if (code !in 200..299) {
            Log.w(TAG, "Recognition returned HTTP $code")
            return@withContext RecognitionOutcome.Failed("http $code")
        }

        parse(text)
    }

    private fun parse(text: String): RecognitionOutcome = runCatching {
        val root = JSONObject(text)
        val track = root.optJSONObject("track")
            ?: return@runCatching RecognitionOutcome.NoMatch
        val title = track.optString("title").ifEmpty {
            return@runCatching RecognitionOutcome.NoMatch
        }
        val images = track.optJSONObject("images")
        val match = root.optJSONArray("matches")?.optJSONObject(0)
        RecognitionOutcome.Match(
            Recognised(
                title = title,
                artist = track.optString("subtitle").ifEmpty { null },
                artworkUrl = images?.optString("coverarthq")?.ifEmpty { null }
                    ?: images?.optString("coverart")?.ifEmpty { null },
                isrc = track.optString("isrc").ifEmpty { null },
                shazamKey = track.optString("key").ifEmpty { null },
                offsetSeconds = match?.optDouble("offset", 0.0) ?: 0.0,
                timeSkew = match?.optDouble("timeskew", 0.0) ?: 0.0,
                frequencySkew = match?.optDouble("frequencyskew", 0.0) ?: 0.0,
            )
        )
    }.getOrElse {
        Log.w(TAG, "Could not read the recognition response", it)
        RecognitionOutcome.Failed("parse")
    }

    companion object {
        private const val TAG = "ShazamClient"
        /** Below this a recording has nothing in it; a real match produced 920. */
        private const val MIN_USEFUL_PEAKS = 30
        private const val ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"
        private const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)"
    }
}
