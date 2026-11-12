/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube

import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the car's search asks YouTube Music, and how long the answer takes: the song filter, for an
 * artist and for a song title, the way MediaLibrarySessionCallback.searchCar asks it (#112). The
 * car waits for one answer, so the time matters as much as the songs.
 *
 * Skipped unless CAR_SEARCH_PROBE=1, so an ordinary run never touches the network. A few requests:
 *
 *     CAR_SEARCH_PROBE=1 ./gradlew :innertube:test --tests "*CarSearchProbe*" -i
 *
 * Other queries with CAR_SEARCH_QUERIES joined by ";;".
 */
class CarSearchProbe {

    private val defaults = listOf("Daft Punk", "Bohemian Rhapsody", "Vasco Rossi", "U2")

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("CAR_SEARCH_PROBE") == "1")
        val queries = System.getenv("CAR_SEARCH_QUERIES")?.split(";;")?.filter { it.isNotBlank() } ?: defaults
        for ((gl, hl) in listOf("IT" to "it", "US" to "en")) {
            YouTube.locale = YouTubeLocale(gl = gl, hl = hl)
            println("CAR ######## locale $hl-$gl ########")
            for (query in queries) {
                val start = System.nanoTime()
                val result = YouTube.search(query, FILTER_SONG)
                val ms = (System.nanoTime() - start) / 1_000_000
                val items = result.getOrNull()?.items.orEmpty()
                val songs = items.filterIsInstance<SongItem>()
                println("CAR   q=\"$query\" -> ${songs.size} songs of ${items.size} items in $ms ms${result.exceptionOrNull()?.let { " THREW $it" } ?: ""}")
                songs.take(3).forEach {
                    println("CAR       '${it.title}' by '${it.artists.joinToString { a -> a.name }}' [${it.id}]")
                }
            }
        }
    }
}
