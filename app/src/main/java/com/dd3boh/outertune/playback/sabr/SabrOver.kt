/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import android.os.SystemClock
import android.util.Log
import com.dd3boh.outertune.sabr.SabrFile
import com.dd3boh.outertune.utils.StreamFamily
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.findSabrProtocolException
import com.metrolist.innertubex.sabr.sabrRequestOrigin
import com.metrolist.innertubex.sabr.toSabrBootstrap
import com.zionhuang.innertube.YouTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.metrolist.innertubex.models.response.PlayerResponse as SabrPlayerResponse

/**
 * A song the walk has an answer for, started over SABR instead of its direct address: the same
 * answer read into InnerTubeX's model (the SABR fields are not in ours), the format the walk chose,
 * a [SabrFile] reading it, and the sabr: address that stands for it ([SabrStarts]). Only from a
 * VISIONOS answer for now: a web client's SABR address carries an n to be solved first.
 *
 * Behind the developer's switch "Stream over SABR only" in a debug build: see YTPlayerUtils.sabrOnly.
 */
@OptIn(ExperimentalSabrApi::class)
object SabrOver {
    private const val TAG = "SabrOver"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * The calls SABR makes: over the family the address was issued to, as the direct address's
     * are, and with no redirect followed, since the library refuses them itself. No overall time
     * limit, since a song is read for as long as it plays.
     */
    private val calls = StreamFamily.Calls(
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    )
    private val clients = ConcurrentHashMap<OkHttpClient, HttpClient>()

    /** InnerTubeX's own lines, into ours. It leaves addresses, tokens and cookies out by design. */
    private val libraryLog = InnerTubeLogger { e ->
        val line = "${e.tag}: ${e.message}" + if (e.details.isEmpty()) "" else " ${e.details}"
        when (e.level) {
            InnerTubeLogLevel.DEBUG -> Log.d(TAG, line)
            InnerTubeLogLevel.INFO -> Log.i(TAG, line)
            InnerTubeLogLevel.WARN -> Log.w(TAG, line)
            InnerTubeLogLevel.ERROR -> Log.e(TAG, line)
        }
    }

    private fun httpFor(url: String): HttpClient {
        val okHttp = calls.clientFor(url)
        return clients.getOrPut(okHttp) {
            HttpClient(OkHttp) {
                engine { preconfigured = okHttp }
                expectSuccess = false
                // Ktor follows them by itself otherwise, before the library could refuse them.
                followRedirects = false
            }
        }
    }

    /**
     * A download's clock, as a player [DOWNLOAD_PACE] times faster than real time would read from
     * where the stream began. A SABR stream asks for the next stretch by where the player is; with
     * no player on the song the library waits a minute for each ten seconds of it, and a song of
     * 3:43 would have taken 22 minutes to download (16 Oct 2026, every answer still accepted).
     */
    fun downloadClock(startMs: Long, now: () -> Long = SystemClock::elapsedRealtime): () -> Long {
        val opened = now()
        return { startMs + (now() - opened) * DOWNLOAD_PACE }
    }

    /** How many times faster than real time a download's clock runs: a 3:43 song in about a minute. */
    const val DOWNLOAD_PACE = 4

    /**
     * [data] with its address replaced by a sabr: address for the same format, or null when the
     * answer it came from cannot be streamed over SABR (said in the log). [forDownload] paces the
     * stream by [downloadClock] instead of the player's position.
     */
    fun over(videoId: String, data: YTPlayerUtils.PlaybackData, forDownload: Boolean = false): YTPlayerUtils.PlaybackData? = runCatching {
        val answer = YouTube.playerAnswers.latest(videoId, "VISIONOS")
        if (answer == null) {
            Log.w(TAG, "[$videoId] no VISIONOS answer kept to start SABR from")
            return null
        }
        val theirs = json.decodeFromString(SabrPlayerResponse.serializer(), answer.text)
        val formats = theirs.streamingData?.adaptiveFormats.orEmpty()
        val format = formats.firstOrNull { it.itag == data.format.itag && it.lastModified == data.format.lastModified?.toString() }
            ?: formats.firstOrNull { it.itag == data.format.itag }
        if (format == null) {
            Log.w(TAG, "[$videoId] the VISIONOS answer has no itag ${data.format.itag}")
            return null
        }
        val bootstrap = theirs.toSabrBootstrap(
            clientId = answer.client.clientId.toInt(),
            clientVersion = answer.client.clientVersion,
            audioFormat = format,
            requestUserAgent = answer.client.userAgent,
            requestOrigin = sabrRequestOrigin(answer.client.clientName),
        )
        val length = format.contentLength ?: data.format.contentLength
        val durationMs = format.approxDurationMs?.toLongOrNull()
            ?: data.videoDetails?.lengthSeconds?.toLongOrNull()?.times(1000)
        if (length == null || durationMs == null) {
            Log.w(TAG, "[$videoId] no length or duration for itag ${format.itag}")
            return null
        }
        val http = httpFor(bootstrap.serverAbrStreamingUrl)
        val file = SabrFile(
            stream = { startMs ->
                SabrAudioStream(
                    http, bootstrap,
                    initialPlayerTimeMs = startMs,
                    // Asked for as a player asks: by where it really is (SabrClock), or for a
                    // download as a faster player would be (downloadClock).
                    playbackPositionMs = if (forDownload) downloadClock(startMs) else { { SabrClock.positionIn(videoId) } },
                    logger = libraryLog,
                    onResponse = { d ->
                        Log.i(
                            TAG,
                            "[$videoId] SABR response ${d.requestNumber} at ${d.requestPlayerTimeMs} ms: http ${d.httpStatus}, " +
                                "${d.selectedSegmentCount} segments, ${d.responseBytes} bytes, retries ${d.transientRetryCount}, " +
                                "no progress ${d.noProgressRequestCount}, protection ${d.protectionStatus}, backoff ${d.backoffTimeMs} ms, " +
                                "readahead ${d.targetAudioReadaheadMs} ms" + (d.failureCategory?.let { ", $it: ${d.failureMessage}" } ?: ""),
                        )
                    },
                ).chunks()
            },
            contentLength = length,
            durationMs = durationMs,
            // Protection still "pending" after InnerTubeX's five answers: a new stream at the byte
            // owed (SabrFile.restartOn). A real refusal, status 3, is not carried on.
            restartOn = { e ->
                e.findSabrProtocolException()?.let {
                    it.kind == SabrFailureKind.ATTESTATION_REQUIRED && "(status 2," in (it.message ?: "")
                } == true
            },
        )
        val address = SabrStarts.put(
            SabrStarts.Start(
                videoId, format.itag, format.lastModified?.toLongOrNull() ?: 0, file,
                expiresAtMs = System.currentTimeMillis() + data.streamExpiresInSeconds * 1000L,
                pacedByPlayer = !forDownload,
            )
        )
        // The client's own name between the brackets, as the walk's lines have it.
        Log.i(TAG, "[$videoId] [VISIONOS] SABR start made: itag ${format.itag}, $length bytes, ${durationMs / 1000} s")
        data.copy(streamUrl = address)
    }.onFailure { Log.w(TAG, "[$videoId] could not start SABR", it) }.getOrNull()
}
