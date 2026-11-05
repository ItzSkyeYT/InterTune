/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import com.dd3boh.outertune.BuildConfig
import java.io.File

/**
 * Debug builds only: with the file [FILE] in filesDir, an album page's request fails as the app's
 * client fails when YouTube answers 404 NOT_FOUND, so what the page does with that answer can be
 * seen on an emulator, with an album in the library and no account. The file names the album ids
 * to fail, one a line, or none to fail them all. Read when the app's window starts, as the files of
 * AutoBlocker and YouTubeRegion are. Nothing in a release.
 */
object AlbumAnswerPretend {
    const val FILE = "pretend-album-not-found"

    /** The ids to fail (empty for all), or null when nothing is pretended. */
    @Volatile
    private var ids: Set<String>? = null

    fun pretendFrom(context: Context) {
        ids = if (BuildConfig.DEBUG) {
            val file = File(context.filesDir, FILE)
            if (file.exists()) runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
            else null
        } else null
    }

    /** What to answer the page of [albumId] with in place of YouTube, or null to ask YouTube. */
    fun failureFor(albumId: String): Throwable? {
        val named = ids ?: return null
        if (named.isNotEmpty() && albumId !in named) return null
        return IllegalStateException(NOT_FOUND_ANSWER)
    }

    /**
     * The words of the client's exception (ktor's ClientRequestException, expectSuccess as in
     * InnerTube.kt) for YouTube's answer to a browse id it has nothing under: Google's standard
     * NOT_FOUND body.
     */
    internal const val NOT_FOUND_ANSWER = "Client request(POST https://music.youtube.com/youtubei/v1/browse?prettyPrint=false) " +
        "invalid: 404 Not Found. Text: \"{ \"error\": { \"code\": 404, \"message\": \"Requested entity was not found.\", " +
        "\"errors\": [ { \"message\": \"Requested entity was not found.\", \"domain\": \"global\", \"reason\": \"notFound\" } ], " +
        "\"status\": \"NOT_FOUND\" } }\""
}
