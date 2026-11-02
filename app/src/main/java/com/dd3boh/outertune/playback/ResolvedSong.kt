/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.playback.downloadManager.SongFileNames
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

/** The start of the cache key a song file is read under: see [downloadedKey]. Nothing is cached under it. */
const val SONG_FILE_KEY = "songfile:"

/**
 * The cache key the player reads a download in a folder under. A song file (SongFileNames: the .opus
 * or .m4a a download is saved as with Unreleased.DOWNLOAD_FILES) holds other bytes than the stream the
 * song cache keeps under the song's id, so it is read under a key of its own, and the song cache
 * neither reads nor writes it: under the id, a part of the stream cached earlier would be joined to the
 * rest of the file, two containers in one song. A download saved as the stream itself keeps the id.
 * In every build, so the files keep playing after an update without the flag.
 */
fun downloadedKey(mediaId: String, fileName: String?): String =
    if (fileName != null && SongFileNames.isSongFile(fileName)) SONG_FILE_KEY + mediaId else mediaId
