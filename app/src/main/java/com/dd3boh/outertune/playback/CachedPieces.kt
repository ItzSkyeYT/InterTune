/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration

/**
 * The files media3 cached a song in, read as one stream in their order: each is opened only once the
 * one before it is used up, and closed after it, so nothing of the song is held in memory. Moving a
 * download into the download folder used to read the whole song into one array first, and a song over
 * about 128 MB, a long mix, ran the app out of memory (#67, 31 Oct 2026).
 */
internal fun cachedPieces(files: List<File>): InputStream {
    val rest = files.iterator()
    return SequenceInputStream(object : Enumeration<InputStream> {
        override fun hasMoreElements() = rest.hasNext()
        override fun nextElement(): InputStream = FileInputStream(rest.next())
    })
}
