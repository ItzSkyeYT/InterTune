/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HomePage

/**
 * Which carousel of YouTube's home feed is the Quick picks row.
 *
 * Found by name first and by content second. The feed carries several carousels that are lists of
 * songs to the parser, and the row used to be filled from whichever came last: an account whose feed
 * led with "Covers and remixes" and ended with "Long listens" got hour-long mixes under the Quick
 * picks heading. The shelf whose title is the app's own word for Quick picks wins outright; the word
 * is localised, so it matches YouTube's in the same language. Failing that, the first song shelf
 * whose items are mostly under ten minutes, which is what a row of songs looks like and a row of
 * mixes does not. The feed arrives in batches, so the choice is made over all of them at once, not
 * batch by batch.
 */
object QuickPicksShelf {
    /** An item this long or shorter is a song rather than a mix, in seconds. */
    const val SONG_MAX_SEC = 600

    /** The share of a shelf's items that must be songs for the shelf to pass on content alone. */
    const val SONG_SHARE = 0.6

    /** A shelf lifted into the row, and whether it was found by its title or only by its content. */
    class Lift(val section: HomePage.Section, val titled: Boolean)

    /** A list shelf made only of songs: the shape of the row. Card shelves and mixed shelves are not it. */
    fun isSongShelf(section: HomePage.Section): Boolean =
        section.itemsPerColumn != null && section.items.isNotEmpty() && section.items.all { it is SongItem }

    /**
     * The share of a shelf's items that are at most [SONG_MAX_SEC] long. An item without a duration
     * counts as a song: YouTube's own Quick picks shelf sends none, and an unknown length is not
     * evidence of a mix.
     */
    fun songShare(section: HomePage.Section): Double {
        val songs = section.items.filterIsInstance<SongItem>()
        if (songs.isEmpty()) return 0.0
        return songs.count { (it.duration ?: 0) <= SONG_MAX_SEC }.toDouble() / songs.size
    }

    /** The shelf of [page] that is the row, if any: the one titled [title], else the first that is mostly songs. */
    fun choose(page: HomePage, title: String): Lift? {
        val candidates = page.sections.filter(::isSongShelf)
        val wanted = title.trim()
        candidates.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }?.let { return Lift(it, titled = true) }
        return candidates.firstOrNull { songShare(it) >= SONG_SHARE }?.let { Lift(it, titled = false) }
    }

    /** Whether [section] is a song shelf that is mostly songs: what the row's pool is widened with. */
    fun lendsSongs(section: HomePage.Section): Boolean = isSongShelf(section) && songShare(section) >= SONG_SHARE
}
