/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.MediaMetadata

/**
 * When an album carries on with a radio after its last song, and which song the radio is asked from.
 *
 * A radio or a mix keeps itself going: its queue holds the seed of its next page
 * (MultiQueueObject.playlistId) and the service asks for that page when five songs are left. An
 * album holds no seed, so with Auto load more songs on it was still played to its last song and
 * then the music stopped. A listener asked about it on 2 Nov 2026: after an album "the app doesn't
 * play new songs; it just stops".
 *
 * YouTube Music carries on from the last song with a radio, and so does this, for albums and for
 * nothing else (his word of 2 Nov 2026: endless playback is for albums and radios). A playlist,
 * the liked songs, a search, the history, a folder, files on the phone and a queue made by hand
 * end where they end, as they always did. A queue knows it came from an album by its origin
 * (MultiQueueObject.origin): the album page, the play button on an album's tile and Play in an
 * online album's menu start it with [PlayOrigin.ALBUM], and the queue is saved with it, so an
 * album comes back from a restart still an album.
 *
 * [seed] names the song. The service asks for its radio as it asks for a radio's next page and
 * appends what is new to the queue, and from then on the queue is a radio, so the page after that
 * is the radio's own business. The songs the album held when the radio joined stay the listener's:
 * no pass takes one out, and Add to queue puts a song after them (MusicService.onMediaItemTransition).
 *
 * Once for each end a queue has. A page that brings nothing new is not asked for again from the
 * same song in the same run ([Asked]), so an album the radio cannot extend is not asked at every
 * song change. An album given more songs has another last song, which is a new end and is asked for.
 *
 * No Android in here, so it is tested.
 */
object EndlessQueue {

    /**
     * Songs from the end, the one playing counted, at which an album asks. The same five as a
     * radio's next page, which is asked for at the song change where this many are left.
     */
    const val NEAR_END = 5

    /** What was asked: [seed], the song a radio was asked from, in the queue's run [run]. */
    data class Asked(val run: String, val seed: String)

    /**
     * The song to ask a radio from, or null when nothing is to be asked now. Read at every song
     * change.
     *
     * @param origin how the queue was started (MultiQueueObject.origin, a [PlayOrigin] code):
     * only an album asks.
     * @param queue the songs of the queue in the order they play, shuffle taken in.
     * @param playing the place of the song playing in the player, and [playingId] its id.
     * @param inPlayer how many songs the player holds. The queue is the one the player plays only
     * when it holds as many and is at one of them; a list a car or another app put in the player
     * is not the board's current queue, and its radio would be appended to the wrong list.
     * @param run the queue's run (AdaptiveQueue.runKey): playing the album again begins another,
     * which is asked for again.
     * @param hasNextPage true for a radio or a mix, whose next page the service asks for already.
     * @param repeatOff false for repeat all and repeat one, where the queue has no end.
     * @param online false when there is no network or YouTube is refusing us. Nothing is asked
     * then, and nothing is spent: the next song change asks.
     * @param asked what was asked last in this process, null for nothing.
     */
    fun seed(
        origin: Int,
        queue: List<MediaMetadata>,
        playing: Int,
        playingId: String?,
        inPlayer: Int,
        run: String,
        hasNextPage: Boolean,
        autoLoadMore: Boolean,
        repeatOff: Boolean,
        online: Boolean,
        asked: Asked?,
    ): String? {
        if (origin != PlayOrigin.ALBUM.code) return null
        if (!autoLoadMore || !repeatOff || !online || hasNextPage) return null
        if (playingId == null || inPlayer != queue.size || playing !in queue.indices) return null
        if (queue[playing].id != playingId) return null
        if (inPlayer - playing > NEAR_END) return null
        // A file on the phone has no YouTube id (SongEntity.generateSongId), so no radio: an album
        // of files only is left to end. The radio of the last song that has an id does for an
        // album that mixes the two.
        val last = queue.lastOrNull { !it.isLocal } ?: return null
        if (asked != null && asked.run == run && asked.seed == last.id) return null
        return last.id
    }
}
