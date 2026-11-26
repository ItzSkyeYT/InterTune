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
    const val ALBUM = "album:"
    const val ARTIST = "artist:"

    /**
     * The order kept: [library], or, until something is dragged after an update, the playlists'
     * own order of the first tester build ([playlists], their ids one a line).
     */
    fun saved(library: String?, playlists: String?): List<String> =
        PlaylistOrder.decode(library).ifEmpty { PlaylistOrder.decode(playlists).map { PLAYLIST + it } }

    /** The playlists' ids, in the order kept. */
    fun playlistIds(saved: List<String>): List<String> = idsOf(saved, PLAYLIST)

    /** The ids of one kind ([prefix]: [ALBUM], [ARTIST] or [PLAYLIST]), in the order kept. */
    fun idsOf(saved: List<String>, prefix: String): List<String> =
        saved.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

    /**
     * The whole order after a drag on a page that shows a part of the library, [part] being that
     * page's whole list as dropped: its keys take the places [saved] gives the part, in their new
     * order, so the rest stays where it was. Keys [saved] has not seen yet take places at the front,
     * where the Library page shows them; a playlist of the part that is gone is dropped, and an album
     * or an artist not shown keeps its place ([keepsPlace]).
     */
    fun refill(saved: List<String>, part: List<String>, inPart: (String) -> Boolean): List<String> {
        val dropped = part.distinct()
        val present = dropped.toHashSet()
        // Where it was: a key of another part, or one of this part not shown that keeps its place.
        fun stays(key: String) = !inPart(key) || (key !in present && keepsPlace(key))
        val kept = saved.filter { stays(it) || it in present }
        val known = kept.filterNot(::stays).toHashSet()
        val next = dropped.iterator()
        val front = List(dropped.count { it !in known }) { next.next() }
        return front + kept.map { if (stays(it)) it else next.next() }
    }

    /**
     * An album or an artist not shown keeps its place in the order: one of a blocked artist comes
     * back where it was put once unblocked, and one whose like or bookmark was taken back where it
     * was if it is given again. A drag while an artist was blocked threw its albums and itself out of
     * the order, and they came back at the top (26 Nov 2026). A playlist not shown is gone.
     */
    fun keepsPlace(key: String): Boolean = key.startsWith(ALBUM) || key.startsWith(ARTIST)
}
