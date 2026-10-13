/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** A file on the phone, served as it is, read from wherever the receiver asks. */
class FileCastSong(private val file: File, override val mimeType: String) : CastSong {
    override val length: Long = file.length()

    override fun openAt(position: Long): CastReader {
        val input = RandomAccessFile(file, "r")
        try {
            input.seek(position)
        } catch (e: IOException) {
            input.close()
            throw e
        }
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = input.read(buffer, offset, length)
            override fun close() = input.close()
        }
    }
}

/** An InputStream as a [CastReader]. Closing the reader closes the stream. */
class StreamCastReader(private val input: InputStream) : CastReader {
    override fun read(buffer: ByteArray, offset: Int, length: Int) = input.read(buffer, offset, length)
    override fun close() = input.close()
}

/**
 * Opens a stream url at a position, and when the open fails, asks for the url once more and tries
 * what comes back.
 *
 * A googlevideo url expires after some hours, and the receiver asks for its pieces over the whole
 * length of the song, a seek back included. A url the server resolved at the start of a long
 * session can therefore be refused halfway through. [refresh] resolves the song again at the
 * position and gives a url for the same encoding, or null when it cannot, in which case the first
 * failure is what the receiver hears about. One retry, not a loop: a url refused twice in a row is
 * not an expiry.
 */
class RefreshingOpener(
    url: String,
    private val open: (url: String, position: Long) -> CastReader,
    private val refresh: (position: Long) -> String?,
) {
    @Volatile
    var url: String = url
        private set

    fun openAt(position: Long): CastReader {
        val tried = url
        return try {
            open(tried, position)
        } catch (e: IOException) {
            val fresh = runCatching { refresh(position) }.getOrNull()
            if (fresh == null || fresh == tried) throw e
            url = fresh
            open(fresh, position)
        }
    }
}

/**
 * The type and length to announce for a cached copy of a song.
 *
 * The cache keeps bytes under the song's id and nothing about what they are. The copy's own first
 * bytes ([sniffed]) are the best word on its type. The format row says what the song was last
 * fetched as, which is usually the copy, but not after a fetch in another encoding that never
 * replaced it, so its length is only believed when its type agrees with the bytes. The length the
 * cache itself recorded ([cacheLength], below 1 when none) is the copy's own and comes first.
 * With neither, the length is unknown and the copy is served whole, to its end.
 */
object CachedCopy {
    fun describe(sniffed: String?, rowMime: String?, cacheLength: Long, rowLength: Long?): Pair<String, Long> {
        val stored = rowMime?.let(CastMime::bare)
        val mime = sniffed ?: stored ?: CastMime.UNKNOWN
        val rowAgrees = stored != null && (sniffed == null || sniffed == stored)
        val length = when {
            cacheLength > 0 -> cacheLength
            rowAgrees && rowLength != null && rowLength > 0 -> rowLength
            else -> -1L
        }
        return mime to length
    }
}
