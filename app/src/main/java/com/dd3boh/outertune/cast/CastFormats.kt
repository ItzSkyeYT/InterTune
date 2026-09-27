/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/** What a receiver said about the types it plays. */
sealed interface FormatSupport {
    /**
     * It said nothing that helps: an FCast v3 receiver, which has no way to say, or a DLNA
     * renderer that lists only `*`. The song goes as it is, and one failure earns one retry as AAC.
     */
    data object Unknown : FormatSupport

    /** It listed the types it takes, lower case and without parameters. */
    data class Listed(val mimeTypes: Set<String>) : FormatSupport
}

/** How one song goes to one receiver. */
sealed interface FormatChoice {
    /** As the app has it: the cached copy, the download, the file, or the stream it would play. */
    data object AsIs : FormatChoice

    /** Fetched again as AAC in MP4 (YouTube's itag 140), because the receiver will not take ours. */
    data object Aac : FormatChoice

    /** Nothing this receiver lists can be made from this song. */
    data class Unplayable(val mimeType: String) : FormatChoice
}

/**
 * Opus where the receiver says it plays it, AAC otherwise (DECISION.md, and design.md section 5).
 *
 * YouTube songs usually come as Opus in WebM, which every FCast receiver plays and most DLNA TVs,
 * AV receivers and speakers do not. YouTube also offers the same song as AAC in MP4, so a renderer
 * that lists MP4 and not WebM is sent that. A local file has only itself, so a renderer that does
 * not list its type cannot be sent it.
 */
object CastFormats {
    /** What an AAC fetch is served as. */
    const val AAC_MIME = "audio/mp4"

    /**
     * Types that name the same files. A renderer lists whichever spelling its firmware chose, and
     * a WebM file is Matroska, so a renderer that reads Matroska reads it too.
     */
    private val families: List<Set<String>> = listOf(
        setOf("audio/webm", "video/webm", "audio/x-matroska", "video/x-matroska", "audio/matroska", "video/matroska"),
        setOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/x-mp4", "audio/mp4a", "video/mp4"),
        setOf("audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg", "audio/mpg"),
        setOf("audio/flac", "audio/x-flac"),
        setOf("audio/ogg", "application/ogg", "audio/x-ogg", "audio/vorbis"),
        setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave"),
        setOf("audio/aac", "audio/aacp", "audio/x-aac", "audio/vnd.dlna.adts"),
        setOf("audio/aiff", "audio/x-aiff"),
    )

    /** Whether a receiver that listed [listed] plays files of [mimeType]. */
    fun plays(listed: Set<String>, mimeType: String): Boolean {
        val type = CastMime.bare(mimeType)
        if (type in listed) return true
        val family = families.firstOrNull { type in it } ?: return false
        return listed.any { it in family }
    }

    /**
     * How a song of [mimeType] goes to a receiver with [support]. [canFetchAac] says whether an AAC
     * copy can be had at all: true for a YouTube song, false for a local file.
     */
    fun choose(support: FormatSupport, mimeType: String, canFetchAac: Boolean): FormatChoice = when (support) {
        FormatSupport.Unknown -> FormatChoice.AsIs
        is FormatSupport.Listed -> when {
            plays(support.mimeTypes, mimeType) -> FormatChoice.AsIs
            canFetchAac && !isAac(mimeType) && plays(support.mimeTypes, AAC_MIME) -> FormatChoice.Aac
            else -> FormatChoice.Unplayable(CastMime.bare(mimeType))
        }
    }

    /**
     * Whether a song the receiver failed to play is worth one more try as AAC. Only when the
     * receiver never said what it plays: one that listed our type and still failed has a
     * problem AAC will not fix, and one that did not list it was sent AAC already.
     */
    fun retryAsAac(support: FormatSupport, sent: FormatChoice, mimeType: String, canFetchAac: Boolean): Boolean =
        support == FormatSupport.Unknown && sent == FormatChoice.AsIs && canFetchAac && !isAac(mimeType)

    private fun isAac(mimeType: String) = plays(setOf(AAC_MIME), mimeType)
}
