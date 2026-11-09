/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.R
import com.dd3boh.outertune.cast.CastReader
import com.dd3boh.outertune.cast.CastSong
import com.dd3boh.outertune.cast.StreamCastReader
import com.dd3boh.outertune.playback.CastSongDataSource
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * A file in a folder the person picked, read as a song the decoder can open (Export to folder,
 * Unreleased.DOWNLOAD_FORMATS): from any position, through the content resolver, as the cast server
 * reads a download in a folder.
 */
internal class DocumentCastSong(
    private val context: Context,
    private val uri: Uri,
    override val length: Long,
    override val mimeType: String,
) : CastSong {
    override fun openAt(position: Long): CastReader {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        try {
            input.channel.position(position)
        } catch (e: IOException) {
            // A provider that hands out a pipe cannot seek: read up to the position instead.
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
}

/** What a song's audio is and how long, as the phone's own extractor reads it from the file or the bytes. */
internal object SourceProbe {
    class Info(val codec: SourceCodec?, val durationUs: Long)

    fun of(song: CastSong): Info {
        val data = CastSongDataSource(song)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(data)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val codec = when (mime) {
                    MediaFormat.MIMETYPE_AUDIO_OPUS -> SourceCodec.OPUS
                    MediaFormat.MIMETYPE_AUDIO_AAC -> SourceCodec.AAC
                    MediaFormat.MIMETYPE_AUDIO_MPEG -> SourceCodec.MP3
                    MediaFormat.MIMETYPE_AUDIO_FLAC -> SourceCodec.FLAC
                    MediaFormat.MIMETYPE_AUDIO_RAW -> SourceCodec.WAV
                    else -> null
                }
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                return Info(codec, duration)
            }
            return Info(null, 0)
        } finally {
            runCatching { extractor.release() }
            data.close()
        }
    }
}

/**
 * A new file in [dir] called [name], with what [write] puts in it, or nothing at all: a write that
 * fails deletes the file it began, so that no half of a song is left in a folder. The type is the
 * one Android gives the extension, as a download's is, since a provider may add the extension of
 * another type to the name. Returns the file, or null when the folder would not make one.
 */
internal fun writeInto(context: Context, dir: DocumentFile, name: String, write: (OutputStream) -> Unit): DocumentFile? {
    val extension = name.substringAfterLast('.', "")
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val file = dir.createFile(mimeType, name) ?: return null
    try {
        val out = context.contentResolver.openOutputStream(file.uri) ?: throw IOException("Could not open $name")
        out.use { stream ->
            val buffered = BufferedOutputStream(stream, 64 * 1024)
            write(buffered)
            buffered.flush()
        }
    } catch (e: Exception) {
        file.delete()
        throw e
    }
    return file
}

/**
 * Whether [dir] holds a file called [name] already. An export leaves such a file as it is and says so: a
 * folder's own way with a second file of a name is "name (1)", which an export run again would pile up,
 * and nothing the person has in the folder is ever replaced.
 */
internal fun hasFile(dir: DocumentFile, name: String): Boolean = runCatching { dir.findFile(name) }.getOrNull() != null

/** An export's result as one line: "12 songs exported, 1 skipped: not downloaded". */
internal fun exportLine(context: Context, result: ExportResult): String =
    ExportSummary.parts(result).joinToString(", ") { (part, n) ->
        val plural = when (part) {
            ExportSummary.Part.EXPORTED -> R.plurals.export_exported
            ExportSummary.Part.NOT_ON_PHONE -> R.plurals.export_skipped_not_downloaded
            ExportSummary.Part.ALREADY_THERE -> R.plurals.export_skipped_there
            ExportSummary.Part.LOCAL -> R.plurals.export_skipped_local
            ExportSummary.Part.FAILED -> R.plurals.export_failed
        }
        context.resources.getQuantityString(plural, n, n)
    }
