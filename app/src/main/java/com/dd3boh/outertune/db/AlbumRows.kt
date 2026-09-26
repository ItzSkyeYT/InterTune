/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.models.MediaMetadata

/** The decisions behind which album row a song goes into. */
object AlbumRows {

    /**
     * The stored album a song being recorded belongs to, or null for a new one to be made under
     * the album's own id.
     *
     * [byId] is the row with the album's id. Only an album from a file on the phone is matched by
     * title, through [localByTitle], since the scanner gives each of its songs a new id and the
     * title is what groups them. A YouTube album has a real id: matching it by title filed a
     * Journey "Greatest Hits" song into a saved Queen "Greatest Hits", and made up a random id
     * for the rest, an album nothing could open, refetch or share.
     */
    fun storedAlbumFor(
        album: MediaMetadata.Album,
        byId: AlbumEntity?,
        localByTitle: (String) -> AlbumEntity?,
    ): AlbumEntity? = byId ?: if (album.isLocal) localByTitle(album.title) else null
}
