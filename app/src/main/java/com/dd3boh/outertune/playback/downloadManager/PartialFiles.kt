/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * A song's file in the download folder is written under a name of its own and renamed once whole
 * (DownloadDirectoryManagerOt.writeWhole), so a file with a song's name is never half a song.
 *
 * Written in place, a move the app was ended in (swiped away, killed in the background halfway
 * through hundreds of songs) left half a file under the song's own name, tags first: the next scan
 * took it for the song, which then played cut short, and the next Move took it as moved already and
 * deleted the whole copy inside the app (review of 0.13, F5). A file with this name's end is never
 * any song's, and one a move left behind goes at the next full listing.
 */
object PartialFiles {
    /** Ends the name of a file being written. No brackets: a name's "[id]" says which song a file is. */
    const val SUFFIX = ".intertune-part"

    /** The type it is made with: a provider keeps the name as it is given for this one, and adds no extension. */
    const val MIME_TYPE = "application/octet-stream"

    /** A part this old is no move's any more: a song is written into the folder in seconds, converted ones included. */
    const val LEFT_BEHIND_MS = 10 * 60 * 1000L

    /** The name [fileName] is written under until it is whole. */
    fun nameWhileWritten(fileName: String): String = fileName + SUFFIX

    /** Whether [name] is a file being written, or one a move left half written ("Song.opus (1).intertune-part" too). */
    fun isPartial(name: String?): Boolean = name != null && name.endsWith(SUFFIX, ignoreCase = true)

    /** Whether a part last written at [lastModified] was left behind by a move that ended, at [now]. 0 is not known: kept. */
    fun leftBehind(lastModified: Long, now: Long): Boolean = lastModified > 0 && now - lastModified >= LEFT_BEHIND_MS
}
