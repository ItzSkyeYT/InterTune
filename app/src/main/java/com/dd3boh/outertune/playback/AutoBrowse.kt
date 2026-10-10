/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.net.Uri

/**
 * What Android Auto is shown since the rework (Unreleased.AUTO_REWORK), by the ids it is handed.
 *
 * A car's screen is for somebody who is driving: a few large things to tap, each of which plays
 * at once, and nothing to read down. So the first tab is "For you", rows of covers: the queue
 * there is to carry on with, the liked songs and the downloads in a new order, then Home's own
 * Quick picks, Keep listening and Forgotten favourites. Playlists are a tab of covers, each
 * opening on a row that shuffles it. The library keeps albums and artists as covers and its
 * songs newest first and no more than a couple of hundred. The last tab is what was played lately.
 *
 * The ids: "home/<section>/<song>" for a song of a row of For you, "recent/<song>",
 * "shuffle/liked", "shuffle/downloaded" and "shuffle/playlist/<playlist>" for a list in a new
 * order, "resume/queue" for the queue there is. PlayRequest reads them back.
 */
object AutoBrowse {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val RECENT = "recent"
    const val SHUFFLE = "shuffle"
    const val RESUME = "resume"

    const val QUICK = "quick"
    const val KEEP = "keep"
    const val FORGOTTEN = "forgotten"
    val SECTIONS = listOf(QUICK, KEEP, FORGOTTEN)

    const val LIKED = "liked"
    const val DOWNLOADED = "downloaded"
    const val PLAYLIST_PREFIX = "playlist/"

    /** The songs in one row of covers: what can be taken in at a glance, two rows of the car's grid. */
    const val MOST = 12

    /** The songs of the library's own list and of Recent: more than that is not read at the wheel. */
    const val MOST_SONGS = 200
    const val MOST_RECENT = 30

    /**
     * The songs of Quick picks, by id: the ones Home showed last come first, because Home knows
     * which source the listener chose for that row, then the library's own, none of them twice.
     */
    fun picks(shown: List<String>, library: List<String>, most: Int = MOST): List<String> =
        (shown + library).distinct().take(most)
}

/**
 * A cover for the car. Android Auto does not load a picture from a web address: it wants one of
 * the app's own, a content address, which AutoArtProvider answers by fetching the cover once and
 * keeping it. The address names a song, an album, an artist or a playlist by its id and nothing
 * else, so that the provider can only ever be asked for a cover of something in the library.
 */
object AutoArt {
    const val SONG = "song"
    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val PLAYLIST = "playlist"
    private val KINDS = setOf(SONG, ALBUM, ARTIST, PLAYLIST)

    fun authority(context: Context): String = "${context.packageName}.autoart"

    fun uri(context: Context, kind: String, id: String): Uri =
        Uri.Builder().scheme("content").authority(authority(context)).appendPath(kind).appendPath(id).build()

    /** The kind and the id an address names, or null for anything that is not one of ours. */
    fun parse(segments: List<String>): Pair<String, String>? =
        if (segments.size == 2 && segments[0] in KINDS && segments[1].isNotEmpty()) segments[0] to segments[1] else null
}
