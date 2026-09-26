/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata

/**
 * Whether the cache holds the whole of [key]: its length is known and every byte of it is there.
 * A song skipped partway holds its start and nothing more, and a check over one chunk says yes to
 * it. The same test the Offline tab makes.
 */
fun Cache.holdsWhole(key: String): Boolean {
    val length = ContentMetadata.getContentLength(getContentMetadata(key))
    return length > 0 && isCached(key, 0, length)
}

/**
 * Whether the cache holds the start of [key] but not all of it.
 *
 * Such a copy is only safe to carry on from with the very stream it came from. The cache knows a
 * song by its id alone, so a stream in another format (the quality setting or the network has
 * changed since) was written on from where the copy stops, and the file held two encodings.
 */
fun Cache.holdsPartFromStart(key: String): Boolean =
    getCachedLength(key, 0, 1) > 0 && !holdsWhole(key)
