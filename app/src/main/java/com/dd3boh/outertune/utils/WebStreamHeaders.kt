/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.YouTubeClient
import okhttp3.HttpUrl
import okhttp3.Interceptor

/**
 * What a web client's stream address is fetched with.
 *
 * An app client's address (VISIONOS, IOS) is fetched with whatever the http library says of
 * itself and nothing else, and plays. The apps that play from YouTube Music's web client fetch its
 * addresses the way the page does: the browser's User-Agent the request was made with, and the
 * page's Origin and Referer (InnerTubeX's buildHeaders, ArchiveTune's StreamClientUtils). The
 * experiment's first plays sent the http library's own name and no Origin, to the check and from
 * the player alike (9 Oct 2026).
 *
 * Which client an address belongs to is read from the address: its c parameter names the client
 * it was issued to. So the check and the player need not be told, and an address that was kept
 * and is played again later is fetched as it was checked.
 *
 * Only the experiment's clients are known here. Every other address goes out as it always has.
 */
object WebStreamHeaders {
    private const val STREAM_HOST_SUFFIX = ".googlevideo.com"

    /** The page each web client's player runs on. */
    private val ORIGINS = mapOf(
        "WEB_REMIX" to YouTubeClient.ORIGIN_YOUTUBE_MUSIC,
        "WEB_EMBEDDED_PLAYER" to "https://www.youtube.com",
    )

    /** The headers for [url], or none when it is not a web client's address on a stream host of YouTube's. */
    fun of(url: HttpUrl): Map<String, String> {
        if (!url.isHttps || !url.host.endsWith(STREAM_HOST_SUFFIX)) return emptyMap()
        val origin = ORIGINS[url.queryParameter("c")] ?: return emptyMap()
        return mapOf(
            "User-Agent" to YouTubeClient.USER_AGENT_WEB,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to origin,
            "Referer" to "$origin/",
        )
    }

    /** Puts [of] on every request it applies to, the check's and the player's alike. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val headers = of(request.url)
        if (headers.isEmpty()) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().apply { headers.forEach { (name, value) -> header(name, value) } }.build())
        }
    }
}
