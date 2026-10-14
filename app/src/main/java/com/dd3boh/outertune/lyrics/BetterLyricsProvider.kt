/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.constants.EnableBetterLyricsKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Better Lyrics: lyrics timed word by word, looked up by title, artist, length and album. It
 * answers with TTML, which the app's parser already reads with its word timings and second voices
 * (SemanticLyrics' parseTtml), so the answer is handed on as it comes.
 */
object BetterLyricsProvider : LyricsProvider {
    override val name = "Better Lyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsKey] ?: true

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun getLyrics(query: LyricsQuery): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val address = BetterLyrics.address(query.searchTitle, query.artists.firstOrNull() ?: query.artist, query.duration, query.album)
            val request = Request.Builder().url(address).header("Accept", "application/json").header("User-Agent", "InterTune").build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> BetterLyrics.ttmlOf(response.body?.string().orEmpty()) ?: throw NoSuchElementException("Better Lyrics has no lyrics for this song")
                    // Better Lyrics was reached and said it has none: an answer, as LRCLIB's empty list is.
                    404 -> throw NoSuchElementException("Better Lyrics has no lyrics for this song")
                    // Anything else is the service not working, which is not an answer about the song.
                    else -> throw IOException("Better Lyrics answered ${response.code}")
                }
            }
        }
    }
}

/** The parts of the Better Lyrics lookup that need no network, apart so they can be tested. */
internal object BetterLyrics {
    private const val HOST = "lyrics-api.boidu.dev"

    fun address(title: String, artist: String, durationSec: Int, album: String?): HttpUrl =
        HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("getLyrics")
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .apply {
                if (durationSec > 0) addQueryParameter("d", durationSec.toString())
                if (!album.isNullOrBlank()) addQueryParameter("al", album)
            }
            .build()

    /** The TTML in an answer, or null when it has none or is not what was expected. */
    fun ttmlOf(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["ttml"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
