/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem

/**
 * The import's searches as they go to YouTube.
 *
 * As a visitor, whatever "Use login for browsing content" says. An import is hundreds of searches
 * that nobody typed. Sent as the account they would carry its name: whether YouTube then keeps
 * them in the account's search history has not been looked at, and a burst that it takes for a
 * script is better left unsigned. Nothing an import looks up needs the account: it asks for
 * songs by name, and what it makes stays on the phone.
 */
object ImportSearch {
    /** Songs only: what the matcher scores, and what the match rate was measured on. */
    suspend fun songs(query: String): List<SongItem> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, anonymous = true).getOrThrow().items.filterIsInstance<SongItem>()
}
