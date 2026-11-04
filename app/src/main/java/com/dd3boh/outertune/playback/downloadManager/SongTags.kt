/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * What a download saved as a song file carries inside (Unreleased.DOWNLOAD_FILES): what any
 * player shows, and the song's link, which is how the app knows the file again, since its name
 * ("Artist - Title.opus") no longer holds the id. No Android in here, so it is tested.
 */
data class SongTags(
    val videoId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val cover: Cover? = null,
) {
    /** The artists as the app shows them, joined with a comma, which its own scanner splits again. */
    val artist: String? get() = artists.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }

    /** Where the song is on YouTube Music: kept in the file's comment, and the id the app reads back. */
    val link: String get() = "https://music.youtube.com/watch?v=$videoId"

    /**
     * The Vorbis comments of an Ogg Opus file, in this order: the link comes before the picture,
     * so reading the id back stops long before the picture's bytes. The year twice: DATE is the
     * Vorbis field players read, and Android's media store, which other apps ask, reads YEAR.
     */
    fun vorbisComments(): List<String> = buildList {
        add("TITLE=$title")
        artist?.let { add("ARTIST=$it") }
        album?.takeIf { it.isNotBlank() }?.let { add("ALBUM=$it") }
        year?.takeIf { it > 0 }?.let { add("DATE=$it"); add("YEAR=$it") }
        track?.takeIf { it > 0 }?.let { add("TRACKNUMBER=$it") }
        add("COMMENT=$link")
        cover?.let { add("METADATA_BLOCK_PICTURE=" + base64(it.flacPictureBlock())) }
    }
}

private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Standard base64 with padding. java.util.Base64 is from API 26, and android.util.Base64 does nothing in a JVM test. */
internal fun base64(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        out.append(BASE64[b0 shr 2])
        out.append(BASE64[((b0 and 0x03) shl 4) or (b1 shr 4)])
        out.append(if (i + 1 < bytes.size) BASE64[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
        out.append(if (i + 2 < bytes.size) BASE64[b2 and 0x3F] else '=')
        i += 3
    }
    return out.toString()
}

/** A cover picture as it goes into a file: JPEG or PNG only, the two every player and car reads. */
class Cover(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int, val depth: Int) {

    /**
     * The picture as a FLAC PICTURE block, which is what an Ogg file's METADATA_BLOCK_PICTURE
     * comment holds, base64 encoded: type 3 is the front cover.
     */
    fun flacPictureBlock(): ByteArray {
        val mime = mimeType.toByteArray(Charsets.US_ASCII)
        val out = java.nio.ByteBuffer.allocate(32 + mime.size + bytes.size)
        out.putInt(3)
        out.putInt(mime.size).put(mime)
        out.putInt(0) // no description
        out.putInt(width).putInt(height).putInt(depth).putInt(0)
        out.putInt(bytes.size).put(bytes)
        return out.array()
    }

    companion object {
        /** [bytes] as a cover, or null when they are neither a JPEG nor a PNG whose size can be read. */
        fun of(bytes: ByteArray): Cover? = jpeg(bytes) ?: png(bytes)

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
        private fun u32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)

        private fun png(b: ByteArray): Cover? {
            val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            if (b.size < 26 || !signature.indices.all { b[it] == signature[it] }) return null
            if (String(b, 12, 4, Charsets.US_ASCII) != "IHDR") return null
            val width = u32(b, 16)
            val height = u32(b, 20)
            val channels = when (u8(b, 25)) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return null }
            if (width <= 0 || height <= 0) return null
            return Cover(b, "image/png", width, height, channels * u8(b, 24))
        }

        /** The size from the first frame header (SOFn) before the scan starts. */
        private fun jpeg(b: ByteArray): Cover? {
            if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
            var i = 2
            while (i + 3 < b.size) {
                if (u8(b, i) != 0xFF) return null
                val marker = u8(b, i + 1)
                when {
                    marker == 0xFF -> { i++; continue }  // fill byte
                    marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue }
                    marker == 0xD9 || marker == 0xDA -> return null
                }
                val length = u16(b, i + 2)
                val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isFrame && i + 9 < b.size) {
                    val height = u16(b, i + 5)
                    val width = u16(b, i + 7)
                    val depth = u8(b, i + 4) * u8(b, i + 9)
                    if (width <= 0 || height <= 0) return null
                    return Cover(b, "image/jpeg", width, height, depth)
                }
                i += 2 + length
            }
            return null
        }
    }
}

object SongFileNames {
    /** The extensions of the song files the app writes. A file with one of them names no id. */
    val EXTENSIONS = setOf("opus", "m4a")

    /** Kept short of the 255 bytes a name may have, with room for the " (1)" a second file gets. */
    private const val MAX_NAME_BYTES = 200

    fun isSongFile(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * "Artist - Title.opus", or "Title.opus" for a song with no artist, or when the title already
     * starts with the artist and a dash, as video titles often do. Made safe for every file system
     * a phone, a card or a car's USB stick may have: no character FAT refuses, no dot at the start
     * (a hidden file) or at the end, no control character, and no longer than [MAX_NAME_BYTES].
     */
    fun of(tags: SongTags, extension: String): String {
        val title = clean(tags.title).ifEmpty { tags.videoId }
        val artist = tags.artist?.let(::clean)?.takeIf { it.isNotEmpty() }
        val base = if (artist == null || title.startsWith("$artist - ", ignoreCase = true)) title else "$artist - $title"
        return cut(base, MAX_NAME_BYTES - extension.length - 1).trimEnd(' ', '.') + "." + extension
    }

    private fun clean(text: String): String = text
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex(":\\s"), " - ")
        .replace(Regex("[/\\\\|:]"), "-")
        .replace("\"", "'")
        .replace(Regex("[*?<>]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimStart('.', ' ')
        .trimEnd('.', ' ')

    /** At most [maxBytes] in UTF-8, cut between two characters, never inside one. */
    private fun cut(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val next = if (Character.isHighSurrogate(text[end]) && end + 1 < text.length) end + 2 else end + 1
            val size = text.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end = next
        }
        return text.substring(0, end).trimEnd()
    }

    private val VIDEO_ID = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")

    /** The video id in a YouTube or YouTube Music link, as [SongTags.link] writes it, or null. */
    fun videoIdIn(text: String): String? = VIDEO_ID.find(text)?.groupValues?.get(1)

    private val ID_IN_NAME = Regex("\\[([A-Za-z0-9_-]{11})]\\.[^.]+$")

    /** The id in a name like "Title [dQw4w9WgXcQ].opus", the way the old downloads and yt-dlp name files. */
    fun idInName(name: String): String? = ID_IN_NAME.find(name)?.groupValues?.get(1)
}
