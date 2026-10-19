/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.DonorsCacheKey
import com.dd3boh.outertune.extensions.isInternetConnected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The names under README > Donors, read from the README itself, so a name added there shows in
 * About > Support InterTune without an app update and no name is written into the app.
 *
 * A name is the bold text that opens a cell of the Donors table. The bold words around the table
 * (the thanks above it, "Want your name here?" below) sit outside any cell and are never taken.
 * The README is asked for when the note opens; the last list is kept in the settings store, so it
 * shows offline and while the request is out. A failed request keeps that list.
 */
object Donors {
    const val README_URL = "https://raw.githubusercontent.com/ItzSkyeYT/InterTune/visionos-fix/README.md"
    private const val TAG = "Donors"

    private val heading = Regex("""(?m)^##\s+Donors\s*$""")
    private val nextHeading = Regex("""(?m)^##\s""")
    private val nameCell = Regex("""<td[^>]*>\s*<b>([^<]{1,60})</b>""")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** The names in the README's Donors section, in the order they are listed. */
    fun parse(readme: String): List<String> {
        val start = heading.find(readme) ?: return emptyList()
        val rest = readme.substring(start.range.last + 1)
        val section = rest.substring(0, nextHeading.find(rest)?.range?.first ?: rest.length)
        return nameCell.findAll(section)
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun unescape(text: String) = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")

    /** The list from the last time the README answered, or none. */
    suspend fun cached(context: Context): List<String> =
        context.dataStore.data.first()[DonorsCacheKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** Asks the README again; on any failure the last list stands. */
    suspend fun refresh(context: Context): List<String> = withContext(Dispatchers.IO) {
        val last = cached(context)
        if (!context.isInternetConnected()) return@withContext last
        val readme = runCatching {
            client.newCall(Request.Builder().url(README_URL).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: run {
            Log.i(TAG, "README not read, keeping the last list of ${last.size}")
            return@withContext last
        }
        val names = parse(readme)
        context.dataStore.edit { it[DonorsCacheKey] = names.joinToString("\n") }
        names
    }
}
