/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.constants.DownloadBitrate
import com.dd3boh.outertune.constants.DownloadFormat

/** What a song's audio is, as far as the choice of what to do with it goes. */
enum class SourceCodec { OPUS, AAC, MP3, FLAC, WAV }

/**
 * The bitrate an MP3 or an AAC is encoded at (Unreleased.DOWNLOAD_FORMATS). A file converted from
 * a lossy stream cannot be better than the stream: YouTube sends Opus at about 50 to 70 kbps on
 * Low and about 130 to 160 on High and Highest, and AAC at 48 (HE), 129 and, on a Premium account,
 * 256. So Auto keeps the standard rate that holds what was sent without waste, and any other rate
 * only changes the size of the file.
 *
 * MP3 needs more bits than Opus or AAC for the same sound, which is why the usual stream, about
 * 130 to 160 kbps, gets 256 as an MP3 and 192 as an AAC, a step above the stream. No Android in here.
 */
object AudioBitrate {
    /** Under this many bits a second a stream is one of the small ones: Opus 249 and 250, HE-AAC. */
    const val LOW_BELOW_BPS = 100_000L

    /** Up to this a stream is the usual one: Opus 251 at 127 to 160 kbps, AAC 140 at 129. Above it, Premium's AAC. */
    const val HIGH_UP_TO_BPS = 170_000L

    /**
     * Auto's rate in kbps for [format] (MP3 or M4A) from a stream of [sourceBps] bits a second, or
     * null when that is not known, taken for the usual stream.
     *
     * | stream           | MP3 | AAC |
     * |------------------|-----|-----|
     * | under 100 kbps   | 128 | 128 |
     * | up to 170 kbps   | 256 | 192 |
     * | above 170 kbps   | 320 | 256 |
     */
    fun auto(format: DownloadFormat, sourceBps: Long?): Int {
        require(format.hasBitrate) { "$format has no bitrate" }
        val tier = when {
            sourceBps == null || sourceBps <= 0 -> 1
            sourceBps < LOW_BELOW_BPS -> 0
            sourceBps <= HIGH_UP_TO_BPS -> 1
            else -> 2
        }
        return when (format) {
            DownloadFormat.MP3 -> intArrayOf(128, 256, 320)[tier]
            else -> intArrayOf(128, 192, 256)[tier]
        }
    }

    /** The rate in kbps that [choice] means for a stream of [sourceBps]: the fixed rate, or Auto's. A fixed rate is honoured, above Auto's too. */
    fun resolve(choice: DownloadBitrate, format: DownloadFormat, sourceBps: Long?): Int =
        choice.kbps ?: auto(format, sourceBps)

    /** Whether [choice] is a fixed rate above what Auto would give: the one a note says cannot add quality YouTube did not send. */
    fun isAboveAuto(choice: DownloadBitrate, format: DownloadFormat, sourceBps: Long?): Boolean =
        choice.kbps != null && choice.kbps > auto(format, sourceBps)
}

/** What is done with one song: saved as it is, or encoded to [format] at [kbps] (null for the formats with no bitrate). */
sealed interface ConvertPlan {
    /** Original: the stream rewrapped as an .opus or an .m4a, or a file copied as it is. Nothing is encoded. */
    data object AsIs : ConvertPlan

    data class Encode(val format: DownloadFormat, val kbps: Int?) : ConvertPlan
}

/** The choices around the file format that need no Android, so that they are tested. */
object DownloadFormats {
    /** The format downloads are saved as: the stored one, or Original when the flag is held back. */
    fun effective(setting: DownloadFormat, offered: Boolean): DownloadFormat =
        if (offered) setting else DownloadFormat.ORIGINAL

    /** A stored setting by its name; a name that is none of the formats (a restored backup of a later version) reads as Original. */
    fun formatNamed(name: String?): DownloadFormat = DownloadFormat.entries.firstOrNull { it.name == name } ?: DownloadFormat.ORIGINAL

    /** A stored bitrate by its name; one that is none of the choices reads as Auto. */
    fun bitrateNamed(name: String?): DownloadBitrate = DownloadBitrate.entries.firstOrNull { it.name == name } ?: DownloadBitrate.AUTO

    /** Whether Settings shows the Bitrate row under [format]: MP3 and M4A (AAC) only. */
    fun bitrateRowShown(format: DownloadFormat): Boolean = format.hasBitrate

    /**
     * What to do with a song whose audio is [source], at [sourceBps] bits a second when that is
     * known, for the format [format] and the bitrate [bitrate].
     *
     * - Original saves the stream as it was sent.
     * - A song already in the format asked for is kept as it is: a song YouTube sent as AAC is
     *   Original's .m4a, which is what M4A means for it, so that nothing is encoded twice; an MP3, a
     *   FLAC or a WAV file is copied.
     * - Anything else is decoded and encoded, MP3 and M4A at the bitrate the choice means for the
     *   stream, FLAC and WAV with none to pick.
     */
    fun plan(format: DownloadFormat, bitrate: DownloadBitrate, source: SourceCodec, sourceBps: Long?): ConvertPlan = when {
        format == DownloadFormat.ORIGINAL -> ConvertPlan.AsIs
        format == DownloadFormat.M4A && source == SourceCodec.AAC -> ConvertPlan.AsIs
        format == DownloadFormat.MP3 && source == SourceCodec.MP3 -> ConvertPlan.AsIs
        format == DownloadFormat.FLAC && source == SourceCodec.FLAC -> ConvertPlan.AsIs
        format == DownloadFormat.WAV && source == SourceCodec.WAV -> ConvertPlan.AsIs
        format.hasBitrate -> ConvertPlan.Encode(format, AudioBitrate.resolve(bitrate, format, sourceBps))
        else -> ConvertPlan.Encode(format, null)
    }

    /** What a file with this extension holds, or null for one that could be several things (.mka) or none. */
    fun sourceOfExtension(extension: String): SourceCodec? = when (extension.lowercase()) {
        "opus" -> SourceCodec.OPUS
        "m4a" -> SourceCodec.AAC
        "mp3" -> SourceCodec.MP3
        "flac" -> SourceCodec.FLAC
        "wav" -> SourceCodec.WAV
        else -> null
    }
}
