/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube

import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Whether a mashup can be found from the songs Shazam heard inside it.
 *
 * Shazam only knows the pieces. On 24 Sep a run over "Linkin Park / Slipknot / Eminem - Damage
 * [MASHUP]" named Faint again and again and No Love once, and the mashup itself is not in its
 * catalogue. The engine's plan is to search YouTube for the pieces' artists with "mashup" and take a
 * result naming two of them. This prints what those queries return, before the engine relies on it.
 *
 *     MIX_PROBE=1 ./gradlew :innertube:test --tests "*MixSearchProbe*" -i
 *
 * Override the queries with MIX_QUERIES joined by ";;".
 */
class MixSearchProbe {

    private val defaults = listOf(
        "Linkin Park Eminem mashup",
        "Faint No Love mashup",
        "Linkin Park Eminem",
    )

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("MIX_PROBE") == "1")
        val queries = System.getenv("MIX_QUERIES")?.split(";;")?.filter { it.isNotBlank() } ?: defaults
        for ((gl, hl) in listOf("FR" to "fr", "US" to "en")) {
            YouTube.locale = YouTubeLocale(gl = gl, hl = hl)
            println("MIX ######## locale $hl-$gl ########")
            for (query in queries) {
                for ((label, filter) in listOf("VIDEO" to FILTER_VIDEO, "SONG" to FILTER_SONG)) {
                    val result = YouTube.search(query, filter)
                    val songs = result.getOrNull()?.items.orEmpty().filterIsInstance<SongItem>()
                    println("MIX   $label q=\"$query\" -> ${songs.size}${result.exceptionOrNull()?.let { " THREW $it" } ?: ""}")
                    songs.take(5).forEach {
                        println("MIX       '${it.title}' by '${it.artists.joinToString { a -> a.name }}' [${it.id}] ${it.duration}s")
                    }
                }
            }
        }
    }
}
