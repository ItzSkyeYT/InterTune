/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * The routes the player failed on, per song, for a while: the clients whose addresses stopped
 * or were refused while the song played. The song's next resolve asks them last, so a retry
 * starts somewhere else (Metrolist does the same with its failed clients). In memory only, and
 * no Android in here, so it is tested.
 *
 * A route is moved back, never left out: when it is the only one that serves, it still serves.
 */
class SongRoutes(private val keepMs: Long = KEEP_MS, private val most: Int = MOST) {
    private class Marks(val routes: MutableSet<String>, var at: Long)

    /** Oldest mark first, so the song marked longest ago is the one pushed out. */
    private val songs = LinkedHashMap<String, Marks>()

    /** [route] failed for [id] at [now]. A null route, a song with no known client, marks nothing. */
    @Synchronized
    fun mark(id: String, route: String?, now: Long) {
        if (route == null) return
        val marks = songs.remove(id)?.takeIf { now - it.at < keepMs } ?: Marks(mutableSetOf(), now)
        marks.routes += route
        marks.at = now
        songs[id] = marks
        while (songs.size > most) songs.remove(songs.keys.first())
    }

    /** The routes to ask last for [id], none once [keepMs] has passed since the last mark. */
    @Synchronized
    fun avoid(id: String, now: Long): Set<String> =
        songs[id]?.takeIf { now - it.at in 0 until keepMs }?.routes?.toSet() ?: emptySet()

    companion object {
        const val KEEP_MS = 30 * 60 * 1000L
        const val MOST = 200
    }
}
