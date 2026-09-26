/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.models.MediaMetadata

/** The decisions behind which album row a song goes into and when an album's page is written. */
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

    /**
     * Whether a YouTube album page just fetched should be written over the stored album.
     *
     * A saved album arrives with no songs, and any song played from it, or recorded as related to
     * one playing, is mapped to it and counted. Checking the song count for 0 alone then left the
     * album at that one song for good: its page, Play, Shuffle and Download all acted on it. So
     * the page is written whenever it has a song the stored album does not.
     *
     * @param stored the row, null when the album is not stored
     * @param mappedSongIds the songs mapped to it now
     * @param pageSongIds the songs on YouTube's page
     */
    fun pageAddsSongs(stored: AlbumEntity?, mappedSongIds: Collection<String>, pageSongIds: Collection<String>): Boolean =
        stored == null || stored.songCount == 0 || !mappedSongIds.toSet().containsAll(pageSongIds)
}
