/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.dd3boh.outertune.cast.CachedCopy
import com.dd3boh.outertune.cast.CastMime
import com.dd3boh.outertune.cast.CastReader
import com.dd3boh.outertune.cast.CastSong
import com.dd3boh.outertune.cast.CastSource
import com.dd3boh.outertune.cast.CastUnavailableException
import com.dd3boh.outertune.cast.ChunkedReader
import com.dd3boh.outertune.cast.FileCastSong
import com.dd3boh.outertune.cast.RefreshingOpener
import com.dd3boh.outertune.cast.StreamCastReader
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.models.response.PlayerResponse
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The cast server's songs, found by the player's own resolver and read from wherever it says.
 *
 * A song is resolved the moment a receiver first asks for it, exactly as the player resolves one
 * it starts, and with the same effects: a copy fetched at a lower quality is upgraded, a partial
 * copy of another stream is dropped, the format row is written and the song is recovered into the
 * library. What comes back is then read in its own way:
 *
 * - a local file, from the file;
 * - a download in a folder the user picked, through the content resolver;
 * - a cached or downloaded copy, through the player's cache stack, from the cache alone;
 * - a stream, through the same cache stack, 512 KB per request as the player asks, so a song cast
 *   from YouTube is cached on the phone just as one played on it would be.
 *
 * [resolver] is the server's own, so its stream urls never mix with the player's.
 *
 * A receiver that takes no Opus gets the song as AAC instead ([openAac]): resolved straight from
 * YouTube by [fetchAac], read through [directSource] with no cache in the way, and written nowhere.
 * The cache and the format row keep the one encoding the player plays; a second one under the same
 * id would be taken for a partial copy of another stream and cost the good one
 * (design.md section 4).
 */
class ServiceCastSource(
    private val resolver: MusicService.SongResolver,
    private val cacheSource: DataSource.Factory,
    private val downloadCache: Cache,
    private val playerCache: Cache,
    private val contentResolver: ContentResolver,
    private val storedFormat: (mediaId: String) -> FormatEntity?,
    private val directSource: DataSource.Factory,
    private val isLocalFile: (mediaId: String) -> Boolean,
    private val fetchAac: (mediaId: String) -> YTPlayerUtils.PlaybackData,
) : CastSource {

    override fun open(mediaId: String): CastSong = when (val resolved = resolve(mediaId, 0)) {
        is ResolvedSong.LocalFile -> fileSong(resolved.file)
        is ResolvedSong.Downloaded -> documentSong(resolved.file)
        ResolvedSong.Cached -> cachedSong(mediaId)
        is ResolvedSong.KnownStream -> streamSong(mediaId, resolved.url, resolved.format)
        is ResolvedSong.NewStream -> streamSong(mediaId, resolved.data.streamUrl, resolved.data.format)
    }

    override fun openAac(mediaId: String): CastSong {
        if (isLocalFile(mediaId)) throw CastUnavailableException("A song on the phone can only be sent as it is")
        val data = aacStream(mediaId)
        val format = data.format
        val mime = CastMime.bare(format.mimeType)
        val size = format.contentLength?.takeIf { it > 0 } ?: -1L
        val opener = RefreshingOpener(
            data.streamUrl,
            open = { url, position ->
                val chunk = if (size >= 0) minOf(MusicService.CHUNK_LENGTH, size - position) else C.LENGTH_UNSET.toLong()
                openDirect(url.toUri(), position, chunk)
            },
            // Only the same stream carries on from a position; anything else is noise there.
            refresh = { _ -> runCatching { aacStream(mediaId) }.getOrNull()?.takeIf { it.format.itag == format.itag }?.streamUrl },
        )
        return object : CastSong {
            override val mimeType = mime
            override val length = size
            override fun openAt(position: Long): CastReader = ChunkedReader(position, size) { from -> opener.openAt(from) }
        }
    }

    private fun aacStream(mediaId: String): YTPlayerUtils.PlaybackData = try {
        fetchAac(mediaId)
    } catch (e: Exception) {
        throw CastUnavailableException(e.message?.takeIf { it.isNotBlank() } ?: "No AAC stream could be found", e)
    }

    /** A read of [uri] straight from the network, past every cache. */
    private fun openDirect(uri: Uri, position: Long, length: Long): CastReader {
        val source = directSource.createDataSource()
        val spec = DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()
        try {
            source.open(spec)
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
            override fun close() = source.close()
        }
    }

    private fun resolve(mediaId: String, position: Long): ResolvedSong = try {
        resolver.resolve(mediaId, position, C.LENGTH_UNSET.toLong())
    } catch (e: Exception) {
        // The player's own wording, which says what went wrong: no internet, a timeout, the reason
        // YouTube gave for refusing, a file that is gone.
        throw CastUnavailableException(e.message?.takeIf { it.isNotBlank() } ?: "The song could not be found", e)
    }

    private fun fileSong(file: File): CastSong {
        val mime = CastMime.forExtension(file.extension)
            ?: sniff { FileCastSong(file, CastMime.UNKNOWN).openAt(0) }
            ?: CastMime.UNKNOWN
        return FileCastSong(file, mime)
    }

    private fun documentSong(file: DocumentFile): CastSong {
        val uri = file.uri
        val open = { position: Long -> openDocument(uri, position) }
        val mime = file.name?.substringAfterLast('.', "")?.let(CastMime::forExtension)
            ?: file.type?.let(CastMime::bare)?.takeIf { CastMime.extensionFor(it) != "bin" }
            ?: sniff { open(0) }
            ?: CastMime.UNKNOWN
        val size = file.length().takeIf { it > 0 } ?: -1L
        return object : CastSong {
            override val mimeType = mime
            override val length = size
            override fun openAt(position: Long) = open(position)
        }
    }

    private fun openDocument(uri: Uri, position: Long): CastReader {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        try {
            input.channel.position(position)
        } catch (e: IOException) {
            // A provider that hands out a pipe rather than a file cannot seek; read up to the
            // position instead.
            var left = position
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    input.close()
                    throw IOException("Could not reach byte $position of $uri", e)
                }
                left -= skipped
            }
        }
        return StreamCastReader(input)
    }

    private fun cachedSong(mediaId: String): CastSong {
        // The player's own request for a cached song: its id as the uri and as the cache key. A
        // hole in the copy reaches the network with that uri and fails, as it would for the
        // player offline.
        val uri = mediaId.toUri()
        val sniffed = sniff { openCache(uri, mediaId, 0, HEAD_BYTES.toLong()) }
        val row = runCatching { storedFormat(mediaId) }.getOrNull()
        val (mime, size) = CachedCopy.describe(sniffed, row?.mimeType, recordedLength(mediaId), row?.contentLength)
        return object : CastSong {
            override val mimeType = mime
            override val length = size

            override fun openAt(position: Long): CastReader = ChunkedReader(position, size) { from ->
                openCache(uri, mediaId, from, if (size >= 0) size - from else C.LENGTH_UNSET.toLong())
            }
        }
    }

    private fun streamSong(mediaId: String, url: String, format: PlayerResponse.StreamingData.Format): CastSong {
        val mime = CastMime.bare(format.mimeType)
        val size = format.contentLength?.takeIf { it > 0 } ?: -1L
        if (size > 0) recordLength(mediaId, size)
        val opener = RefreshingOpener(
            url,
            open = { streamUrl, position ->
                val chunk = if (size >= 0) minOf(MusicService.CHUNK_LENGTH, size - position) else C.LENGTH_UNSET.toLong()
                openCache(streamUrl.toUri(), mediaId, position, chunk)
            },
            refresh = { position ->
                // The url may have expired over a long session. Only a url for the very same
                // stream will do: another encoding carried on from this position is noise.
                resolver.forgetStream(mediaId)
                when (val again = resolve(mediaId, position)) {
                    is ResolvedSong.KnownStream -> again.url.takeIf { again.format.itag == format.itag }
                    is ResolvedSong.NewStream -> again.data.streamUrl.takeIf { again.data.format.itag == format.itag }
                    else -> null
                }
            },
        )
        return object : CastSong {
            override val mimeType = mime
            override val length = size
            override fun openAt(position: Long): CastReader = ChunkedReader(position, size) { from -> opener.openAt(from) }
        }
    }

    private fun openCache(uri: Uri, key: String, position: Long, length: Long): CastReader {
        val source = cacheSource.createDataSource()
        val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(length).build()
        try {
            source.open(spec)
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        return object : CastReader {
            // C.RESULT_END_OF_INPUT is -1, the same end a CastReader gives.
            override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
            override fun close() = source.close()
        }
    }

    /** The whole length the caches recorded for [mediaId], the download cache first, or -1. */
    private fun recordedLength(mediaId: String): Long {
        for (cache in listOf(downloadCache, playerCache)) {
            val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(mediaId)) }
                .getOrDefault(C.LENGTH_UNSET.toLong())
            if (length > 0) return length
        }
        return -1
    }

    /**
     * Writes a stream's length into the player's cache when nothing has yet.
     *
     * The cache learns a song's length only from a request left open to the end, and the server's
     * requests are all 512 KB long, so a song cast to the end would be complete in the cache and
     * never known to be: the Offline tab would not list it, and the player would treat it as a
     * partial copy on every play. The stream's length is exact, and a length the cache already
     * holds is left alone. The resolver has dropped any copy from the start in another encoding
     * before it hands out a stream, which is also what keeps the player's own copies whole.
     */
    private fun recordLength(mediaId: String, length: Long) {
        runCatching {
            if (ContentMetadata.getContentLength(playerCache.getContentMetadata(mediaId)) == C.LENGTH_UNSET.toLong()) {
                val mutations = ContentMetadataMutations()
                ContentMetadataMutations.setContentLength(mutations, length)
                playerCache.applyContentMetadataMutations(mediaId, mutations)
            }
        }
    }

    /** The type the first bytes say, or null when they cannot be read or say nothing known. */
    private fun sniff(open: () -> CastReader): String? = runCatching {
        open().use { reader ->
            val head = ByteArray(HEAD_BYTES)
            var count = 0
            while (count < head.size) {
                val read = reader.read(head, count, head.size - count)
                if (read < 0) break
                count += read
            }
            CastMime.sniff(head.copyOf(count))
        }
    }.getOrNull()

    private companion object {
        const val HEAD_BYTES = 64
    }
}
