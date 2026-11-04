/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.DownloadCovers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The cover that goes inside a song file (SongTags.cover): the song's picture at [SIDE] pixels, the
 * size YouTube Music itself offers as large, which car screens show too. Offline, the cover kept for
 * the download (DownloadCovers) stands in. A picture that is neither JPEG nor PNG (WebP) is made a
 * JPEG, which every player reads. Blocking: call it off the main thread.
 */
object SongFileCover {
    const val SIDE = 544

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    fun of(thumbnailUrl: String?, filesDir: File): Cover? {
        val url = thumbnailUrl?.takeIf { it.startsWith("http") } ?: return null
        val bytes = fetch(url.resize(SIDE, SIDE))
            ?: DownloadCovers.keptStandIn(filesDir, url)?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: return null
        return Cover.of(bytes) ?: asJpeg(bytes)?.let(Cover::of)
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun asJpeg(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) out.toByteArray() else null
            }
        } finally {
            bitmap.recycle()
        }
    }
}
