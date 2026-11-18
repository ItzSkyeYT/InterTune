/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.listenbrainz.ListenBrainz
import com.dd3boh.listenbrainz.models.Listen
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.Scrobbler

/**
 * What counts as a play for the services a play is told to, Last.fm and ListenBrainz, in one
 * place, so that the two cannot drift apart.
 */
object ListenRules {

    /**
     * Whether a play is sent. Last.fm's rule, which is ListenBrainz's own ("half the track or 4
     * minutes of the track, whichever is lower") with a floor: a song under thirty seconds never
     * counts. In time actually played, not position, since a song can be seeked around in.
     *
     * @param durationSeconds the recovered length: a song started from search results says -1 in
     *   its metadata, and none of them ever counted.
     */
    fun qualifies(playedMs: Long, durationSeconds: Int): Boolean {
        if (durationSeconds < 30) return false
        val target = minOf(durationSeconds * 1000L / 2, 4 * 60 * 1000L)
        return playedMs >= target
    }

    /**
     * [metadata] as a listen for ListenBrainz, or null when there is nothing to send by: no artist
     * or no title. It carries the first artist, as Last.fm gets (Scrobbler.primaryArtist), the
     * album, the length given, which is the recovered one, and the track number when known; a song
     * from YouTube Music also its video, which gives ListenBrainz a link its own player can play.
     * A local file goes without.
     *
     * @param startedAtSeconds when the play started, as Last.fm gets it; null for playing now.
     */
    fun listenOf(client: ListenBrainz, metadata: MediaMetadata, startedAtSeconds: Long?, durationSeconds: Int): Listen? =
        client.listen(
            artist = Scrobbler.primaryArtist(metadata.artists.map { it.name }) ?: return null,
            track = metadata.title,
            release = metadata.album?.title,
            durationMs = durationSeconds.takeIf { it > 0 }?.let { it * 1000L },
            startedAtSeconds = startedAtSeconds,
            trackNumber = metadata.trackNumber,
            youTubeId = metadata.id.takeUnless { metadata.isLocal },
        )
}
