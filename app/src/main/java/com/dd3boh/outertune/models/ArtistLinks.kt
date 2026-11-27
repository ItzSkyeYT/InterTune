/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.Artist

/**
 * The credited artists that "View artist" offers, in their order: every one with a page or at least a name.
 *
 * YouTube leaves the browse id out of some credits: an artist it has no page for (So What, on Hoax's "Thread The
 * Needle"), Various Artists on a compilation. Opening one navigated to "artist/null", which Navigation reads as a
 * null argument for a route that does not take one, and it threw: the app crashed. They were left out of the
 * choice since, which offered Hoax and Dynamite MC alone for that song (27 Nov 2026). Now each goes where
 * [viewRoute] says.
 */
@JvmName("youTubeArtistsViewable")
fun List<Artist>.viewable(): List<MediaMetadata.Artist> =
    map { MediaMetadata.Artist(id = it.id?.takeIf { id -> id.isNotBlank() }, name = it.name) }.viewable()

fun List<MediaMetadata.Artist>.viewable(): List<MediaMetadata.Artist> =
    filter { !it.id.isNullOrBlank() || it.name.isNotBlank() }

/**
 * Where "View artist" takes [this] artist: its page, by its id; or, for one YouTube gave no page, a search for its
 * name, which is all there is to open for it ([encode] puts the name in the route: String.urlEncode). Never
 * "artist/null". Null for an artist with neither an id nor a name.
 */
fun MediaMetadata.Artist.viewRoute(encode: (String) -> String): String? = when {
    !id.isNullOrBlank() -> "artist/$id"
    name.isNotBlank() -> "search/${encode(name.trim())}"
    else -> null
}
