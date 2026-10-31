/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import coil3.request.ImageResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Covers of downloaded songs, kept as files in the app's own storage so they show with no network.
 *
 * They used to be left to the image cache, Coil's disk cache in cacheDir, which Android empties
 * whenever the phone runs low on space: on 29 Oct 2026 the emulator, its storage 93% full, lost its
 * whole cache folder on a restart, a cover saved a minute before with it (dvd, 27 Oct: downloaded
 * songs without covers offline). A download keeps the cover lists show and the player's small first
 * one here, and [Interceptor] serves them in place of the network, online or not.
 */
object DownloadCovers {
    private const val DIR = "download-covers"

    /** The names kept, read from the folder once and then followed, so the image loader never waits on the disk. */
    private val kept = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var listed = false

    fun dir(base: File) = File(base, DIR)

    /** The file name for [url]: its SHA-1, the same each time and safe for any address. */
    fun nameFor(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun fileFor(base: File, url: String): File = File(dir(base), nameFor(url))

    fun isKept(base: File, url: String): Boolean {
        if (!listed) synchronized(this) {
            if (!listed) {
                dir(base).list()?.filterNot { it.endsWith(".part") }?.let(kept::addAll)
                listed = true
            }
        }
        return nameFor(url) in kept
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    /**
     * Keeps [url]'s image unless it is kept already. Written beside its place and renamed, so a cut
     * leaves nothing half made. Blocking: call it off the main thread.
     */
    fun save(base: File, url: String, fetch: (String) -> ByteArray? = ::download): Boolean {
        if (isKept(base, url)) return true
        val bytes = runCatching { fetch(url) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val target = fileFor(base, url)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val done = runCatching { part.writeBytes(bytes); part.renameTo(target) }.getOrDefault(false)
        if (done) kept += target.name else part.delete()
        return done
    }

    fun remove(base: File, url: String) {
        val target = fileFor(base, url)
        target.delete()
        kept -= target.name
    }

    private fun download(url: String): ByteArray? =
        client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }

    /** The image loader's: a kept cover's file in place of its address. */
    class Interceptor(private val base: File) : coil3.intercept.Interceptor {
        override suspend fun intercept(chain: coil3.intercept.Interceptor.Chain): ImageResult {
            val data = chain.request.data
            if (data is String && data.startsWith("http") && isKept(base, data)) {
                return chain.withRequest(chain.request.newBuilder().data(fileFor(base, data)).build()).proceed()
            }
            return chain.proceed()
        }
    }
}
