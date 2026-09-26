/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.Artist

/**
 * The credited artists that "View artist" can open, in their order.
 *
 * YouTube leaves the browse id out of some credits, Various Artists on a compilation above all.
 * Opening one navigated to "artist/null", which Navigation reads as a null argument for a route
 * that does not take one, and it threw: the app crashed.
 */
fun List<Artist>.withArtistIds(): List<MediaMetadata.Artist> = mapNotNull { artist ->
    artist.id?.takeIf { it.isNotBlank() }?.let { MediaMetadata.Artist(id = it, name = artist.name) }
}

@JvmName("mediaMetadataArtistsWithIds")
fun List<MediaMetadata.Artist>.withArtistIds(): List<MediaMetadata.Artist> = filter { !it.id.isNullOrBlank() }
