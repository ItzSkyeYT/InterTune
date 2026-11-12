/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/**
 * What was sent where, for working out what the speaker is playing: the stretches of a song and of
 * silence, by session frame, and the song the speaker was last heard in. The stream notes each
 * packet it sends ([record]) and looks about every 20 ms where the speaker has got to ([listen]).
 *
 * A stretch shorter than the time between two looks (a seek into a song's last few milliseconds,
 * or a look late on a busy phone) can be passed over between them. It was still played, so it is
 * told as heard: a song seeked to its last 20 ms otherwise never ended, and the queue stopped there.
 *
 * Not thread-safe: the stream holds its lock around every call.
 */
internal class AirPlayHearing(private val maxSpans: Int = 4096) {

    private class Span(val start: Long, var end: Long, val load: Int, val songFrame: Long) {
        val song: Boolean get() = load != 0
    }

    /**
     * The speaker started hearing song [load] at its frame [songFrame] ([song]), or stopped hearing
     * it for silence ([song] false, [songFrame] -1); [atFrame] is the session frame where that
     * stretch begins.
     */
    data class News(val load: Int, val songFrame: Long, val song: Boolean, val atFrame: Long)

    private val spans = ArrayDeque<Span>()
    private var heardLoad = 0
    private var heardSong = false

    /** The song and frame where the last song stretch let go of, before the speaker's point. */
    private var lastSongEnd: Pair<Int, Long>? = null

    /** A new stretch of sending: nothing of it heard yet. */
    fun clear() {
        spans.clear()
        heardSong = false
        heardLoad = 0
        lastSongEnd = null
    }

    /**
     * Notes the packet at session frame [frame], [packetFrames] long: [songFrames] of them from song
     * [load] starting at its [songFrame] (0 for silence), joined to the stretch before when it
     * carries on from it.
     */
    fun record(frame: Long, packetFrames: Int, load: Int, songFrames: Int, songFrame: Long) {
        val end = frame + packetFrames
        if (load != 0 && songFrames > 0) {
            val last = spans.lastOrNull()
            if (last != null && last.song && last.load == load && last.end == frame &&
                last.songFrame + (last.end - last.start) == songFrame
            ) {
                last.end = frame + songFrames
            } else {
                spans.addLast(Span(frame, frame + songFrames, load, songFrame))
            }
            if (songFrames < packetFrames) spans.addLast(Span(frame + songFrames, end, 0, 0))
        } else {
            val last = spans.lastOrNull()
            if (last != null && !last.song && last.end == frame) last.end = end else spans.addLast(Span(frame, end, 0, 0))
        }
        // Only what the speaker has yet to play is needed, with room for a stall.
        while (spans.size > maxSpans) spans.removeFirst()
    }

    /**
     * Where the speaker stands at session frame [heard]: the song and its frame it plays, or, in
     * silence after a song, where that song stopped. Null when nothing of a song has been played in
     * this stretch.
     */
    fun position(heard: Long): Pair<Int, Long>? {
        var last = lastSongEnd
        for (span in spans) {
            if (span.start > heard) break
            if (span.song) last = span.load to (span.songFrame + (minOf(heard, span.end) - span.start))
        }
        return last
    }

    /**
     * What changed since the last look, the speaker being at session frame [heard] now: a song it
     * passed through between the two looks, then the song or the silence it is in, each only when
     * it is news.
     */
    fun listen(heard: Long): List<News> {
        val news = ArrayList<News>(2)
        while (spans.size > 1 && spans.first().end <= heard) {
            val gone = spans.removeFirst()
            if (!gone.song) continue
            lastSongEnd = gone.load to gone.songFrame + (gone.end - gone.start)
            if (!heardSong || heardLoad != gone.load) {
                heardSong = true
                heardLoad = gone.load
                news += News(gone.load, gone.songFrame, true, gone.start)
            }
        }
        val span = spans.firstOrNull()?.takeIf { it.start <= heard && heard < it.end } ?: return news
        if (span.song) {
            if (!heardSong || heardLoad != span.load) {
                heardSong = true
                heardLoad = span.load
                news += News(span.load, span.songFrame, true, span.start)
            }
        } else if (heardSong) {
            heardSong = false
            news += News(heardLoad, -1L, false, span.start)
        }
        return news
    }
}
