/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

/**
 * Track and disc numbers as the tags hold them, read the same way by every scanner.
 *
 * Taggers write "4", "04" or "4/12", the number and the count. TagLib hands that text over as it
 * is, and Integer.parseInt took "4/12" for no number at all: a whole album tagged that way had no
 * track numbers, and sorting a folder by them left it in whatever order the library held it
 * (issue #39).
 */
object TrackTags {

    /** The number a track or disc tag starts with, or null when it has none. 0 counts as none. */
    fun number(raw: String?): Int? =
        raw?.trim()?.takeWhile { it in '0'..'9' }?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * Track and disc from TagLib's property map, as (track, disc): the first value of each that
     * has a number, as TagLibScanner reads them.
     */
    fun fromPropertyMap(map: Map<String, Array<String>>): Pair<Int?, Int?> = Pair(
        map["TRACKNUMBER"]?.firstNotNullOfOrNull { number(it) },
        map["DISCNUMBER"]?.firstNotNullOfOrNull { number(it) },
    )

    /**
     * Track and disc from MediaStore's columns, as (track, disc).
     *
     * CD_TRACK_NUMBER and DISC_NUMBER (Android 11 on) hold the tags' text. Read with getInt, the
     * cursor parsed it as C does, a leading 0 meaning octal: "08" and "09" came out as 0, and so
     * did a missing tag. TRACK, on every version, holds disc * 1000 + track and gives what the
     * text does not; on Android 10 and older it is all there is.
     */
    fun fromMediaStore(cdTrackNumber: String?, discNumber: String?, track: Int?): Pair<Int?, Int?> {
        val packed = track?.takeIf { it > 0 }
        return Pair(
            number(cdTrackNumber) ?: packed?.rem(1000)?.takeIf { it > 0 },
            number(discNumber) ?: packed?.div(1000)?.takeIf { it > 0 },
        )
    }
}
