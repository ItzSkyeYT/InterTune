/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * What a stream server sent when the player opened a song's stream from its start: the type, the
 * length and the first bytes, kept for the last few songs. A player error prints it in its details
 * (streamLines). When no extractor can read a stream, that answer is what tells a web page, an
 * empty answer and a kind of stream the player does not know apart: Timo on Discord, 10 Nov 2026,
 * "now nothing is playable", the client fine and its HEAD 200, then nothing the player could read.
 * Nothing personal is kept: no address, and of the headers only the type.
 */
@UnstableApi
object StreamAnswer {
    /** How many bytes of a stream's start are kept. */
    const val HEAD_BYTES = 16

    private const val SONGS = 8

    private const val TAG = "StreamAnswer"

    private val answers = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > SONGS
    }

    /** The last answer kept for [mediaId], or null. */
    fun of(mediaId: String?): String? = mediaId?.let { synchronized(answers) { answers[it] } }

    private fun keep(mediaId: String, line: String) {
        synchronized(answers) { answers[mediaId] = line }
    }

    /**
     * One line for an answer of [type] and [length] whose first [got] bytes are in [head]; [ended]
     * when the answer ended there. Text is shown as text, anything else as bytes in hex.
     */
    fun describe(type: String?, length: Long, head: ByteArray, got: Int, ended: Boolean = false): String = buildString {
        append(type?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "no type")
        append(", ")
        append(if (length == C.LENGTH_UNSET.toLong()) "length not given" else "$length bytes")
        append(", ")
        val bytes = head.copyOf(got.coerceIn(0, head.size))
        when {
            bytes.isEmpty() -> append(if (ended) "ended with no bytes" else "nothing read")
            bytes.all { it in 0x20..0x7e || it == 0x09.toByte() || it == 0x0a.toByte() || it == 0x0d.toByte() } ->
                append("began \"").append(String(bytes, Charsets.US_ASCII).replace(Regex("\\s+"), " ")).append('"')
            else -> append("began ").append(bytes.joinToString(" ") { "%02x".format(it) })
        }
    }

    /** [upstream] with every stream it opens from its start noted (see [StreamAnswer]). */
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Source(upstream.createDataSource())
    }

    class Source(private val upstream: DataSource) : DataSource {
        private var song: String? = null
        private var type: String? = null
        private var length = C.LENGTH_UNSET.toLong()
        private var head: ByteArray? = null
        private var got = 0

        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val opened = upstream.open(dataSpec)
            val id = dataSpec.key
            // From its start only: that is what the extractors look at, and a later range of the
            // same song must not hide what its start was.
            if (id != null && dataSpec.position == 0L) {
                song = id
                length = opened
                got = 0
                type = upstream.responseHeaders.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
                head = ByteArray(HEAD_BYTES).also { keep(id, describe(type, length, it, 0)) }
            } else {
                song = null
                head = null
            }
            return opened
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = upstream.read(buffer, offset, length)
            val h = head
            val id = song
            if (h != null && id != null && got < h.size) {
                if (read > 0) {
                    val n = minOf(read, h.size - got)
                    System.arraycopy(buffer, offset, h, got, n)
                    got += n
                    keep(id, describe(type, this.length, h, got))
                    // Once a stream's start is in: one line in the log per stream opened.
                    if (got == h.size) runCatching { Log.d(TAG, "$id: ${of(id)}") }
                } else if (read == C.RESULT_END_OF_INPUT) {
                    keep(id, describe(type, this.length, h, got, ended = true))
                    runCatching { Log.d(TAG, "$id: ${of(id)}") }
                    head = null
                }
            }
            return read
        }

        override fun getUri() = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()
    }
}
