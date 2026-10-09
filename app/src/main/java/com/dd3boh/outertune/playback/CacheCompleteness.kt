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

/**
 * Whether a cached part and a stream are the same file, as far as the song's format row can say:
 * the same itag and, where both lengths are known, the same number of bytes.
 *
 * The itag alone was the test, and it was enough while a song's bytes could only come from the
 * song's own id: one id, one itag, one file. A stand-in is another upload under the same itag
 * ([StandIns]), and carried on with it a part would hold the start of one upload and the rest of
 * another. Two uploads of a recording are never the same length to the byte.
 */
fun sameStream(partItag: Int?, partLength: Long?, itag: Int, length: Long): Boolean =
    partItag == itag && (partLength == null || partLength <= 0L || length <= 0L || partLength == length)

/**
 * The same itag and both lengths known and different: surely another file, an upload other than
 * the one the part came from, and not another quality of the same one, which has another itag.
 */
fun anotherUpload(partItag: Int?, partLength: Long?, itag: Int, length: Long): Boolean =
    partItag == itag && partLength != null && partLength > 0L && length > 0L && partLength != length
