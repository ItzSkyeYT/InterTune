/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue

/**
 * Next on a phone in a listening room that someone else is in control of (his words, 23 Nov 2026: "there are moments i
 * can't skip songs to listen independantly"). Its queue is a copy of the room's from the song playing on, never topped
 * up by itself (MusicService's load more, TogetherSession.matchQueue), so when the room has nothing after its song,
 * neither has this phone, and its Next was greyed out: the player offers Next only toward a song in its own queue.
 * Nothing in the room greys it on purpose: a skip there has always taken a guest its own way, Catch up bringing it back.
 *
 * So it stays on ([onOwn]), and with nothing after the song it goes its own way to what the app plays next by itself
 * anywhere else: the song's radio, from the song after it ([RadioAfter]). Whoever is in control skips the room as ever.
 */
internal object RoomSkip {
    /**
     * Whether Next takes this phone its own way to a radio of its song: it [follows] someone in a room, nothing comes after
     * its song here ([hasNext] false), and that song, [songId], is one a radio can be made from, not a file on this phone
     * ([local]). Null [songId]: nothing is playing.
     */
    fun onOwn(follows: Boolean, hasNext: Boolean, songId: String?, local: Boolean): Boolean =
        follows && !hasNext && songId != null && !fromThisPhone(local, songId)

    /**
     * Where the radio of the song [songId] starts so that Next moves on from it: the song after it in the radio's songs
     * [ids], or after [answered], the place YouTube said the radio starts from, when the song itself is not among them (a
     * version of it in its place). The song's own place when nothing comes after it: the radio then plays it on.
     */
    fun startAfter(ids: List<String>, songId: String, answered: Int): Int {
        if (ids.isEmpty()) return 0
        val at = ids.indexOf(songId).takeIf { it >= 0 } ?: answered.coerceIn(0, ids.lastIndex)
        return if (at < ids.lastIndex) at + 1 else at
    }

    /**
     * [song]'s radio as it starts anywhere ([radio], YouTubeQueue.radio), played from the song after [song]: no song of its
     * own to put in the player first (which would be [song] again, from the top), and the radio after it as ever.
     */
    class RadioAfter(private val song: MediaMetadata, private val radio: Queue = YouTubeQueue.radio(song)) : Queue by radio {
        override val preloadItem: MediaMetadata? get() = null

        override suspend fun getInitialStatus(): Queue.Status {
            val status = radio.getInitialStatus()
            return status.copy(mediaItemIndex = startAfter(status.items.map { it.id }, song.id, status.mediaItemIndex))
        }
    }
}
