/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import com.dd3boh.outertune.cast.CastNext
import com.dd3boh.outertune.cast.CastQueue
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.utils.resize

/** How wide a cover on the TV's "Up next" shelf is asked for: a small square, not the TV's whole screen. */
internal const val CAST_SHELF_ART_PX = 256

/** The cover of a song on the shelf: its own, asked of the image host at a small size. Only a web address. */
internal fun castShelfArtwork(stored: String?): String? =
    stored?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.resize(CAST_SHELF_ART_PX, CAST_SHELF_ART_PX)

/** [song] as a line on the TV's "Up next" shelf, or null when it has no words to show. */
internal fun castNext(song: MediaMetadata?): CastNext? {
    if (song == null) return null
    val title = song.title.takeIf { it.isNotBlank() } ?: return null
    return CastNext(
        title = title,
        artist = song.artists.joinToString { it.name }.takeIf { it.isNotEmpty() },
        cover = castShelfArtwork(song.thumbnailUrl),
    )
}

/**
 * The songs that play after the current one, as the TV lists them: the next few of the queue, from
 * the first again when the queue repeats (CastQueue). Read on the player's own thread.
 */
internal fun Player.castUpNext(): List<CastNext> =
    CastQueue.upcoming(currentMediaItemIndex, mediaItemCount, repeatMode == Player.REPEAT_MODE_ALL)
        .mapNotNull { castNext(getMediaItemAt(it).metadata) }
