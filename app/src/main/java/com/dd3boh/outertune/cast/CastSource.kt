/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.Closeable
import java.io.IOException

/**
 * Where the cast server gets its songs. The app's goes through the same resolver the player uses
 * (playback/ServiceCastSource.kt); the tests use a fake.
 */
fun interface CastSource {
    /**
     * Finds [mediaId] and says what it is. Blocking, and it may go to the network, so never on the
     * main thread. Throws [CastUnavailableException] when the song cannot be had.
     */
    fun open(mediaId: String): CastSong
}

/** A song ready to be served: its type, its length, and a way to read it from any byte. */
interface CastSong {
    /** The Content-Type to send, without parameters. */
    val mimeType: String

    /** In bytes, or -1 when nothing recorded it. */
    val length: Long

    /** A reader starting at [position]. The caller closes it. */
    fun openAt(position: Long): CastReader
}

/** Reads a song in pieces, the way InputStream does: up to [length] bytes, -1 at the end. */
interface CastReader : Closeable {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * The song cannot be served, with a reason short enough to send back as the body of a 503: no
 * network, YouTube backing off, a local file gone. The receiver then reports an error, and the
 * player's own skip-or-stop setting decides what happens next.
 */
class CastUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads from [start] to [end] (exclusive; -1 when the end is not known) through sources that may
 * each stop short, opening the next at the position the last one stopped.
 *
 * The player's resolver hands out a remote song 512 KB at a time, because the streams of some
 * YouTube clients refuse anything past that in one request. ExoPlayer copes by opening again; the
 * server has to do the same, or every receiver would hear half a minute of each song. A source
 * that ends without giving a single byte while more is due is an error rather than an endless
 * loop. With the end unknown there is no telling a short source from the true end, so the first
 * end is taken as the end.
 */
class ChunkedReader(
    start: Long,
    private val end: Long,
    private val openChunk: (position: Long) -> CastReader,
) : CastReader {
    private var position = start
    private var current: CastReader? = null
    private var currentGave = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var emptyReads = 0
        while (true) {
            if (end >= 0 && position >= end) return -1
            val reader = current ?: openChunk(position).also {
                current = it
                currentGave = false
            }
            val wanted = if (end >= 0) minOf(length.toLong(), end - position).toInt() else length
            val count = reader.read(buffer, offset, wanted)
            if (count > 0) {
                position += count
                currentGave = true
                return count
            }
            if (count == 0) {
                // Allowed by the contract of a DataSource, but a source stuck there would spin.
                if (++emptyReads > MAX_EMPTY_READS) throw IOException("The source keeps giving nothing at $position")
                continue
            }
            reader.close()
            current = null
            if (end < 0) return -1
            if (!currentGave) throw IOException("The source ended at $position of $end without giving anything")
        }
    }

    override fun close() {
        current?.close()
        current = null
    }

    private companion object {
        const val MAX_EMPTY_READS = 1000
    }
}
