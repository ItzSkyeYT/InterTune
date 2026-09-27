/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/**
 * The content types the cast server sends and the file extensions its urls end in.
 *
 * A receiver decides whether it can play a song from the Content-Type before it reads a byte, and
 * some DLNA renderers look at the extension in the url instead, so both have to be right for the
 * bytes that are actually served, not for what the song was once fetched as.
 */
object CastMime {
    /** What is sent when nothing better is known. Receivers then sniff, or refuse. */
    const val UNKNOWN = "application/octet-stream"

    private val byExtension = mapOf(
        "webm" to "audio/webm",
        "weba" to "audio/webm",
        "mka" to "audio/x-matroska",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "mp4" to "audio/mp4",
        "aac" to "audio/aac",
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        // An .opus file is Opus in Ogg. audio/opus names bare Opus packets, which no file holds.
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "amr" to "audio/amr",
        "wma" to "audio/x-ms-wma",
    )

    private val extensionByMime = mapOf(
        "audio/webm" to "webm",
        "video/webm" to "webm",
        "audio/x-matroska" to "mka",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/m4a" to "m4a",
        "video/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/aacp" to "aac",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/vnd.wave" to "wav",
        "audio/aiff" to "aiff",
        "audio/x-aiff" to "aiff",
        "audio/amr" to "amr",
        "audio/x-ms-wma" to "wma",
    )

    /**
     * The type alone, lower case, without parameters. YouTube's formats say
     * `audio/webm; codecs="opus"`, and a parameter is one more thing a picky renderer can choke on.
     */
    fun bare(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

    /** The type for a file extension, with or without its dot, or null for one not known. */
    fun forExtension(extension: String): String? =
        byExtension[extension.removePrefix(".").lowercase()]

    /** The extension a url for [mimeType] ends in. `bin` for a type not known. */
    fun extensionFor(mimeType: String): String = extensionByMime[bare(mimeType)] ?: "bin"

    /**
     * The type the first bytes of a file say it is, or null when they say nothing known.
     *
     * The cache knows a song by its id alone and keeps no type, and the format row beside it can
     * describe a later fetch rather than the copy, so a cached copy is asked directly. 64 bytes is
     * enough for every check here; WebM names itself in its header, well inside that.
     */
    fun sniff(head: ByteArray): String? {
        fun at(offset: Int, text: String) = head.size >= offset + text.length &&
            text.indices.all { head[offset + it] == text[it].code.toByte() }

        fun byte(index: Int) = if (index < head.size) head[index].toInt() and 0xFF else -1

        return when {
            byte(0) == 0x1A && byte(1) == 0x45 && byte(2) == 0xDF && byte(3) == 0xA3 ->
                if (containsAscii(head, "webm")) "audio/webm" else "audio/x-matroska"
            at(4, "ftyp") -> "audio/mp4"
            at(0, "fLaC") -> "audio/flac"
            at(0, "OggS") -> "audio/ogg"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "audio/aiff"
            at(0, "#!AMR") -> "audio/amr"
            at(0, "ID3") -> "audio/mpeg"
            // ADTS and MPEG audio both start with a run of set bits. ADTS has layer 00, which no
            // MP3 frame has, so it is told apart first.
            byte(0) == 0xFF && (byte(1) and 0xF6) == 0xF0 -> "audio/aac"
            byte(0) == 0xFF && (byte(1) and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> null
        }
    }

    private fun containsAscii(bytes: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        if (bytes.size < needle.size) return false
        return (0..bytes.size - needle.size).any { start ->
            needle.indices.all { bytes[start + it] == needle[it] }
        }
    }
}
