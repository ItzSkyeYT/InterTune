package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata

/**
 * One Play next for a guest following a room: the songs go to the room, not to its own copy of the queue (which the
 * person in control's next queue overwrites). Each Suggest is put right after the current song, so the last one sent
 * ends up first: they are sent in reverse and the list plays in the order it was chosen.
 */
object RoomPlayNext {
    /** A message each: more than this from one album or playlist would flood the room's relays. */
    const val MAX = 30

    class Plan(
        /** To send, in the order of sending (the reverse of the order they play in). */
        val toRoom: List<MediaMetadata>,
        /** Kept on this phone's own queue: a song from a file cannot play on the other phones. */
        val own: List<MediaMetadata>,
        /** Songs left out because of MAX. */
        val dropped: Int,
    )

    fun plan(songs: List<MediaMetadata>): Plan {
        val online = songs.filter { !it.isLocal }
        val kept = online.take(MAX)
        return Plan(kept.reversed(), songs.filter { it.isLocal }, online.size - kept.size)
    }
}
