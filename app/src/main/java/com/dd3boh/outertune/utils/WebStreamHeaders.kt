/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.YouTubeClient
import okhttp3.HttpUrl
import okhttp3.Request

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
 * it was issued to. So nothing that fetches a stream need be told, and an address that was kept
 * and is played again later is fetched as it was checked.
 *
 * And nothing but the address decides. The headers are put on where every fetch of a stream
 * passes, [StreamFamily.Calls], in every build. They used to be put on where the check's client
 * and the player's were built, and only in a build that has the developer options' "Ask YouTube
 * Music's web client first". A release has no such switch, so there the web client the chain
 * asks by itself (YTPlayerUtils.WEB_FALLBACK_CLIENT) would have had its address checked and
 * played as the experiment's first plays were, and a download's client never had them at all.
 *
 * Only web clients are known here. Every other address goes out as it always has.
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

    /**
     * [request] with [of] its address on it, over whatever it had set of them. A request for any
     * other address is handed back as it is.
     */
    fun on(request: Request): Request {
        val headers = of(request.url)
        if (headers.isEmpty()) return request
        return request.newBuilder().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    }
}
