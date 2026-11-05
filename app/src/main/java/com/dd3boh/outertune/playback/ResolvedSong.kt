/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.models.response.PlayerResponse
import java.io.File

/**
 * Where a song's bytes come from, as [MusicService.SongResolver] decided it.
 *
 * The player turns each answer into a request to its cache stack, exactly as it did when the
 * decision was written inside its data source. The cast server reads each in its own way
 * (ServiceCastSource), which is why the decision had to come out where both could ask it.
 */
sealed interface ResolvedSong {
    /** A file on the phone, found by the scan, played as it is. */
    data class LocalFile(val file: File) : ResolvedSong

    /** A song downloaded into a folder the user picked. */
    data class Downloaded(val file: DocumentFile) : ResolvedSong

    /** Whatever the download cache or the player's cache holds under the song's id. */
    data object Cached : ResolvedSong

    /** A stream url this resolver found earlier and that has not expired, and what it streams. */
    data class KnownStream(val url: String, val format: PlayerResponse.StreamingData.Format) : ResolvedSong

    /** A stream just resolved from YouTube. */
    data class NewStream(val data: YTPlayerUtils.PlaybackData) : ResolvedSong
}

/** The start of the cache key a download in a folder is read under: see [downloadedKey]. Nothing is cached under it. */
const val FOLDER_FILE_KEY = "folderfile:"

/**
 * The cache key the player reads a download in a folder under: one of its own, so the song cache
 * neither reads nor writes it, and the file is read from the folder alone.
 *
 * A song file (SongFileNames: the .opus or .m4a a download is saved as with Unreleased.DOWNLOAD_FILES)
 * holds other bytes than the stream the song cache keeps under the song's id: under the id, a part of
 * the stream cached earlier would be joined to the rest of the file, two containers in one song. A
 * download saved as the stream itself (.mka) was read under the id, through the song cache, and so
 * written into it at each play: a download kept twice (KeptOnce), and a part cached from another
 * stream, at another quality, joined to it. In every build, so the files keep playing the same way
 * after an update without the flag.
 */
fun downloadedKey(mediaId: String): String = FOLDER_FILE_KEY + mediaId
