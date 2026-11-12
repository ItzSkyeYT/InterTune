/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A download read as ranges of a megabyte, one after another, instead of one request for the
 * whole stream. YouTube serves an open-ended request at about twice real time and a range at full
 * speed: a 3.6 MB song took 110 s as one request and 0.4 s as 1 MB ranges, and a three-hour mix
 * took 100 minutes on the Pixel 5 (1 Nov 2026, experiments/bugs/dl-speed).
 *
 * Only an open-ended request for a web address is cut up; a request with a length goes as it is.
 * It sits below the cache and the resolver, so the address is resolved once and every range uses
 * it. A server that ignores ranges still works: the web source skips to each range's start.
 */
@OptIn(UnstableApi::class)
class PiecewiseDataSource(private val upstream: DataSource, private val piece: Long = Pieces.PIECE) : DataSource {
    private var spec: DataSpec? = null
    private var pieced = false
    /** Where the next byte is, in the stream. */
    private var at = 0L
    /** The stream's whole length, once an answer has said it. */
    private var total: Long? = null
    /** Bytes read from the range open now: none from a range of unknown length is the end. */
    private var inRange = 0L

    class Factory(private val upstream: DataSource.Factory, private val piece: Long = Pieces.PIECE) : DataSource.Factory {
        override fun createDataSource(): DataSource = PiecewiseDataSource(upstream.createDataSource(), piece)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val scheme = dataSpec.uri.scheme
        pieced = dataSpec.length == C.LENGTH_UNSET.toLong() && (scheme == "http" || scheme == "https")
        if (!pieced) return upstream.open(dataSpec)
        at = dataSpec.position
        inRange = 0L
        upstream.open(dataSpec.subrange(0, piece))
        total = Pieces.totalOf(upstream.responseHeaders)
        return total?.let { it - dataSpec.position } ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!pieced) return upstream.read(buffer, offset, length)
        val asked = checkNotNull(spec) { "read before open" }
        while (true) {
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) {
                at += n
                inRange += n
                return n
            }
            // This range is used up: the next one, unless the stream is.
            val whole = total
            if (whole != null && at >= whole) return C.RESULT_END_OF_INPUT
            if (whole == null && inRange == 0L) return C.RESULT_END_OF_INPUT
            upstream.close()
            inRange = 0L
            upstream.open(asked.subrange(at - asked.position, Pieces.nextLength(at, whole, piece)))
            if (total == null) total = Pieces.totalOf(upstream.responseHeaders)
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            spec = null
            pieced = false
        }
    }
}

/** The arithmetic of [PiecewiseDataSource], apart so a plain test can check it. */
internal object Pieces {
    /** A megabyte: measured fast on 1 Nov 2026, and a three-hour mix is under two hundred of them. */
    const val PIECE = 1L shl 20

    /**
     * The whole stream's length from an answer's headers: the total of its Content-Range
     * ("bytes 0-1048575/3620000"), or, for an answer that is the whole stream (no Content-Range),
     * its Content-Length. Null when neither says, or the range's total is "*".
     */
    fun totalOf(headers: Map<String, List<String>>): Long? {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        header("Content-Range")?.let { range -> return range.substringAfterLast('/', "").trim().toLongOrNull() }
        return header("Content-Length")?.trim()?.toLongOrNull()
    }

    /** How much the next range asks for, from [at]: a [piece], or what is left of [total]. */
    fun nextLength(at: Long, total: Long?, piece: Long): Long = if (total == null) piece else minOf(piece, total - at)
}
