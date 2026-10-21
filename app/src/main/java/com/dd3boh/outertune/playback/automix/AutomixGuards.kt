/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

/**
 * Whether one change of song may be mixed at all, from a snapshot of the player and the settings.
 * Pure, so tested; AutomixController takes the snapshot. A change that may not be mixed is the
 * change of today, and the reason goes in the log.
 *
 * The planner asks some of these again (length, album, the same song), as a second guard; these
 * are the ones only the player knows: what comes next, the repeat mode, casting, the speed, and
 * the audio settings under which a held end would not be what is heard.
 */
object AutomixGuards {

    /** Shorter than this, on either side, a song is played whole. */
    const val MIN_SONG_MS = 60_000L

    /** One side of a change: a queue item as the player has it. */
    class Song(val id: String, val albumId: String?, val durationMs: Long)

    class Snapshot(
        val settingOn: Boolean,
        /** Whether the stage is in the chain for the song playing: it joins a stream only as the stream starts. */
        val stageInChain: Boolean,
        val casting: Boolean,
        val repeatOne: Boolean,
        val current: Song?,
        /** What follows [current], by the timeline with the real repeat and shuffle modes; null at the end of the queue. */
        val next: Song?,
        val speed: Float,
        val pitch: Float,
        val skipSilence: Boolean,
        val headTracking: Boolean,
        val upmix: Boolean,
        /** The experimental Ambience above 0: it delays everything by 21 ms and adds to every drain, so the next song's frames would not be where its opening says. */
        val ambience: Boolean,
        /** The full build's FFmpeg decoders, which the next song's opening is not read with. */
        val ffmpeg: Boolean,
        val sleepTimer: Boolean,
        /** The listener seeked within the last minute of the song playing. */
        val seekedLate: Boolean,
    )

    /** Why this change may not be mixed, or null when it may. */
    fun whyNot(s: Snapshot): String? {
        val current = s.current
        val next = s.next
        return when {
            !s.settingOn -> "AutoMix is off"
            s.casting -> "casting"
            current == null -> "nothing is playing"
            s.repeatOne -> "repeat one is on"
            next == null -> "nothing comes next"
            next.id == current.id -> "the same song comes again"
            sameAlbum(current, next) -> "the two songs are from one album"
            current.durationMs in 0 until MIN_SONG_MS -> "this song is under a minute"
            current.durationMs < 0 -> "this song's length is not known"
            next.durationMs in 0 until MIN_SONG_MS -> "the next song is under a minute"
            next.durationMs < 0 -> "the next song's length is not known"
            s.speed != 1f || s.pitch != 1f -> "the speed or the pitch is not as recorded"
            s.skipSilence -> "skip silence is on"
            s.headTracking -> "head tracking is on"
            s.upmix -> "the surround upmix is on"
            s.ambience -> "the spatial Ambience is on"
            s.ffmpeg -> "FFmpeg decodes this build's audio"
            s.sleepTimer -> "a sleep timer is set"
            s.seekedLate -> "the song was moved within its last minute"
            !s.stageInChain -> "AutoMix was not on yet as this song started"
            else -> null
        }
    }

    /** Two songs of one album run into each other as their album has them. */
    fun sameAlbum(a: Song, b: Song): Boolean {
        val album = a.albumId?.takeIf { it.isNotBlank() } ?: return false
        return album == b.albumId
    }
}
