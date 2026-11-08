/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

/**
 * The library in an order of your own: one order for its albums, artists and playlists, each named
 * by its key (album:, artist: or playlist: and its id, as the Library page knows them), kept as one
 * preference, one key a line. The Library page shows all of it in Custom order and the Playlists
 * page the playlists' part, so the two never disagree.
 */
object LibraryOrder {

    const val PLAYLIST = "playlist:"

    /**
     * The order kept: [library], or, until something is dragged after an update, the playlists'
     * own order of the first tester build ([playlists], their ids one a line).
     */
    fun saved(library: String?, playlists: String?): List<String> =
        PlaylistOrder.decode(library).ifEmpty { PlaylistOrder.decode(playlists).map { PLAYLIST + it } }

    /** The playlists' ids, in the order kept. */
    fun playlistIds(saved: List<String>): List<String> =
        saved.filter { it.startsWith(PLAYLIST) }.map { it.removePrefix(PLAYLIST) }

    /**
     * The whole order after a drag on a page that shows a part of the library, [part] being that
     * page's whole list as dropped: its keys take the places [saved] gives the part, in their new
     * order, so the rest stays where it was. Keys [saved] has not seen yet take places at the front,
     * where the Library page shows them; keys of the part that are gone are dropped.
     */
    fun refill(saved: List<String>, part: List<String>, inPart: (String) -> Boolean): List<String> {
        val dropped = part.distinct()
        val present = dropped.toHashSet()
        val kept = saved.filter { !inPart(it) || it in present }
        val known = kept.filter(inPart).toHashSet()
        val next = dropped.iterator()
        val front = List(dropped.count { it !in known }) { next.next() }
        return front + kept.map { if (inPart(it)) next.next() else it }
    }
}
