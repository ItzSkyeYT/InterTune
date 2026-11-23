/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.os.SystemClock
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.engine.SimilarMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ListenBrainz's similar songs, the 0.14 engine's second source of them now that Last.fm closes on 1 December 2026
 * (experiments/features/listenbrainz/PLAN.md, part 3; experiments/features/recs-014/STEPS.md, step 3). Its public
 * "labs" API, with no account and no token: a song is named by its artist and title (acr-lookup), and the recordings
 * people play near it are asked for by its MusicBrainz id (similar-recordings). Nothing about the listener is in
 * either request, only the song's names, and only for a song the listener allowed ListenBrainz to be asked about.
 *
 * Measured on 18 Nov 2026 on 220 public songs (experiments/recs-trial-1118/REPORT.md): where it answers, people go
 * on to play its songs far more often than YouTube's, but it knows a third of today's songs, so a song it has nothing
 * for keeps YouTube's list. Labs makes no promise of staying the same, so an answer that is not what is expected
 * counts as no answer, never as a crash.
 */
object ListenBrainzLabs {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val gate = Mutex()
    private var nextAt = 0L

    /**
     * The song's MusicBrainz recording id, or null when ListenBrainz does not know it. Throws when the request
     * failed, which is not an answer about the song.
     */
    suspend fun recordingOf(artist: String, title: String): String? = LbLabs.mbidOf(get(LbLabs.lookupAddress(artist, title)))

    /** The songs people play near it, most similar first; empty when it knows none. Throws when the request failed. */
    suspend fun similarTo(mbid: String): List<SimilarMatch.Neighbour> =
        LbLabs.neighboursOf(get(LbLabs.similarAddress(mbid))) ?: throw IOException("ListenBrainz answered something unexpected")

    /**
     * One request at a time and never more than one a second, the most ListenBrainz asks of anyone, with its own
     * count of what is left in its window obeyed: down to the last one, the next waits until the window turns.
     */
    private suspend fun get(url: HttpUrl): String = gate.withLock {
        val wait = nextAt - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", LbLabs.userAgent(BuildConfig.VERSION_NAME))
                    .build()
                client.newCall(request).execute().use { response ->
                    nextAt = SystemClock.elapsedRealtime() + LbLabs.waitAfter(
                        response.header("X-RateLimit-Remaining")?.toIntOrNull(),
                        response.header("X-RateLimit-Reset-In")?.toIntOrNull(),
                    )
                    if (response.code != 200) throw IOException("ListenBrainz answered ${response.code}")
                    response.body?.string().orEmpty()
                }
            }
        } catch (e: IOException) {
            nextAt = maxOf(nextAt, SystemClock.elapsedRealtime() + LbLabs.SPACING_MS)
            throw e
        }
    }
}

/** The parts of [ListenBrainzLabs] that need no network, apart so they can be tested. */
internal object LbLabs {
    const val HOST = "labs.api.listenbrainz.org"

    /** The algorithm the 18 Nov trial measured: sessions over 7,500 days, 50 songs at most. */
    const val ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_15_limit_50_skip_30"

    /** One request a second at most. */
    const val SPACING_MS = 1_000L

    private val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** What ListenBrainz asks every caller to send: the app, its version and where to find it. */
    fun userAgent(version: String) = "InterTune/$version ( https://github.com/ItzSkyeYT/InterTune )"

    fun lookupAddress(artist: String, title: String): HttpUrl =
        HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("acr-lookup").addPathSegment("json")
            .addQueryParameter("artist_credit_name", artist)
            .addQueryParameter("recording_name", title)
            .build()

    fun similarAddress(mbid: String): HttpUrl =
        HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("similar-recordings").addPathSegment("json")
            .addQueryParameter("recording_mbids", mbid)
            .addQueryParameter("algorithm", ALGORITHM)
            .build()

    /** The first match's recording id, when the answer has one shaped like an id. */
    fun mbidOf(answer: String): String? = runCatching {
        (json.parseToJsonElement(answer) as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("recording_mbid")?.jsonPrimitive?.contentOrNull }
            ?.lowercase()?.takeIf { MBID.matches(it) }
    }.getOrNull()

    /**
     * The similar recordings as title and artist, in ListenBrainz's order, the way [SimilarMatch] reads Last.fm's.
     * Null when the answer is not a list at all, which is labs changing rather than knowing nothing.
     */
    fun neighboursOf(answer: String): List<SimilarMatch.Neighbour>? = runCatching {
        val rows = json.parseToJsonElement(answer) as? JsonArray ?: return@runCatching null
        rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val title = o["recording_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val artist = o["artist_credit_name"]?.jsonPrimitive?.contentOrNull?.trim()
            if (title.isEmpty() || artist.isNullOrEmpty()) null else SimilarMatch.Neighbour(title, artist)
        }
    }.getOrNull()

    /**
     * How long to wait before the next request: a second, or until the window turns when ListenBrainz says this was
     * the last request it allows in it.
     */
    fun waitAfter(remaining: Int?, resetInSeconds: Int?): Long =
        if (remaining != null && resetInSeconds != null && remaining <= 1) maxOf(SPACING_MS, (resetInSeconds + 1) * 1_000L)
        else SPACING_MS
}
