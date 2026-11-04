/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.search

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO

/**
 * A page of results starts on the filter its route names. A search edited on a page of results is
 * a new page, and it started on All whatever the page before was on: his search, Artists, an
 * artist opened, back, the words changed, and the results were all kinds again (his note of
 * 30 Oct 2026). The bar now hands the new page the filter of the page it was edited on.
 *
 * By name, not by YouTube's own value: those hold "%3D", which the route's decoding would turn
 * into "=", and the page would no longer know its filter.
 */
object SearchFilters {

    /** The route of a page of results: the words, then the filter's name, none for All. */
    const val ROUTE = "search/{query}?filter={filter}"

    /** The chips' filters by name. All is no filter, and has none. */
    private val NAMES = listOf(
        "songs" to FILTER_SONG,
        "videos" to FILTER_VIDEO,
        "albums" to FILTER_ALBUM,
        "artists" to FILTER_ARTIST,
        "community_playlists" to FILTER_COMMUNITY_PLAYLIST,
        "featured_playlists" to FILTER_FEATURED_PLAYLIST,
    )

    /** The filter a route names, or null, All, for no name or one no chip has. */
    fun of(name: String?): YouTube.SearchFilter? = NAMES.firstOrNull { it.first == name }?.second

    fun nameOf(filter: YouTube.SearchFilter?): String? = NAMES.firstOrNull { it.second == filter }?.first

    /** What goes after the words in a page's route to start it on [filter]: nothing for All. */
    fun routeSuffix(filter: YouTube.SearchFilter?): String = nameOf(filter)?.let { "?filter=$it" }.orEmpty()
}
