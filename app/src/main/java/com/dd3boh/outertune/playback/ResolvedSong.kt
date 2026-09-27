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
