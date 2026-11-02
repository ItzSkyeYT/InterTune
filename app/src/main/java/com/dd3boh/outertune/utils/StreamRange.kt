/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import okhttp3.Interceptor
import okhttp3.Request

/**
 * Every request for a song's bytes from YouTube's stream servers names a range, from 0 when the
 * player asks for the whole song (OkHttpDataSource sends none then).
 *
 * Asked without one, the servers answer at once with the whole file (200) but hold its first bytes
 * back: 4.7 to 7 s for format 249, the small Opus stream Low takes, against 0.4 s for 251; with
 * "Range: bytes=0-" (206) every format's first bytes came in about 0.2 s (measured from the PC on
 * 29 Oct 2026, experiments/bugs/small-format-stall/no_range.py). The pace guard took those seconds
 * for a crawling stream and cut it three times, so on Low, and on Auto with mobile data, songs did
 * not start or stopped after a few seconds.
 */
object StreamRange {
    fun withRange(request: Request): Request =
        if (request.method == "GET" && request.header("Range") == null &&
            request.url.host.endsWith(".googlevideo.com", ignoreCase = true)
        ) request.newBuilder().header("Range", "bytes=0-").build()
        else request

    val interceptor = Interceptor { chain -> chain.proceed(withRange(chain.request())) }
}
