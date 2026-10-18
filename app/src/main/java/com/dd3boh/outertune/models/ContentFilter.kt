/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.HideExplicitKey
import com.dd3boh.outertune.constants.HideVideoSongsKey
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the two switches under Settings > Library and content keep out: explicit songs and
 * albums, and songs that are music videos or uploads. A third for Shorts was taken out before 0.12
 * (his word, 18 Oct 2026): YouTube Music sends none to these places, so it hid nothing.
 *
 * It applies where YouTube chooses what to offer: Home's rows from YouTube (Quick picks among
 * them when YouTube fills it), search results, an artist's page and its lists, and what a radio or
 * autoplay adds to the queue. It does not apply to what the listener opened by name, an album or a
 * playlist, nor to the library and the rows built from it: a stored song does not keep YouTube's
 * marks, so there is nothing to tell an explicit one by.
 */
data class ContentFilter(
    val explicit: Boolean = false,
    val videoSongs: Boolean = false,
) {
    val isOff: Boolean
        get() = !explicit && !videoSongs

    /** Whether [item] is one the switches keep out. A song of unknown kind is kept. */
    fun hides(item: YTItem): Boolean = when (item) {
        is SongItem -> (explicit && item.explicit) || (videoSongs && item.isVideoSong)
        is AlbumItem -> explicit && item.explicit
        else -> false
    }

    fun <T : YTItem> apply(items: List<T>): List<T> = if (isOff) items else items.filterNot(::hides)

    /**
     * A radio's first page without what is hidden, but with the song at [start] even when it is
     * one of them: the listener chose that one. Gives the songs and where that song now stands.
     */
    fun radio(items: List<SongItem>, start: Int): Pair<List<SongItem>, Int> {
        if (isOff) return items to start
        val kept = ArrayList<SongItem>(items.size)
        var at = 0
        items.forEachIndexed { index, item ->
            if (index == start) at = kept.size
            if (index == start || !hides(item)) kept += item
        }
        return kept to at
    }

    /** Home's rows without what is hidden; a row left empty goes. */
    fun home(page: HomePage): HomePage = if (isOff) page else
        page.copy(sections = page.sections.map { it.copy(items = apply(it.items)) }.filter { it.items.isNotEmpty() })

    /** The first page of a search, the same way. */
    fun search(page: SearchSummaryPage): SearchSummaryPage = if (isOff) page else
        page.copy(summaries = page.summaries.map { it.copy(items = apply(it.items)) }.filter { it.items.isNotEmpty() })

    /** An artist's page, the same way: its songs, albums, singles, videos and playlists. */
    fun artist(page: ArtistPage): ArtistPage = if (isOff) page else
        page.copy(sections = page.sections.map { it.copy(items = apply(it.items)) }.filter { it.items.isNotEmpty() })

    companion object {
        fun of(preferences: Preferences) = ContentFilter(
            explicit = preferences[HideExplicitKey] ?: false,
            videoSongs = preferences[HideVideoSongsKey] ?: false,
        )

        private val state = MutableStateFlow(ContentFilter())

        /** The switches as they stand, followed by App from the settings. */
        val changes: StateFlow<ContentFilter> = state

        /**
         * Read by the queues and the pages, which have no settings of their own to follow. Set
         * by App, at start and at every change.
         */
        var current: ContentFilter
            get() = state.value
            set(value) {
                state.value = value
            }
    }
}
