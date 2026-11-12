/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.constants.DownloadFormat
import java.io.Closeable
import java.io.File

/**
 * Makes a file of a [format] from decoded audio, piece by piece (Unreleased.DOWNLOAD_FORMATS): the
 * tags and the cover inside it, written to [file], a temporary one in the app's own storage. The file
 * is whole only when [finish] has returned; a writer closed before that leaves nothing for the caller to
 * use, and the caller deletes the file. Nothing here touches the download folder.
 *
 * The audio is 16-bit, interleaved, mono or stereo: what MediaCodecPcmDecoder gives.
 */
internal interface FileEncoder : Closeable {
    val format: DownloadFormat

    /** The temporary file being written. */
    val file: File

    /** Once, before the first [add]: the audio's sample rate and channels. Refuses what the format cannot hold. */
    fun start(rate: Int, channels: Int)

    /** [frames] frames from the start of [samples], which the caller may reuse as soon as this returns. */
    fun add(samples: ShortArray, frames: Int)

    /** The end of the audio: the file is whole when this returns. */
    fun finish()
}
