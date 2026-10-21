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
    private class Marks(val routes: MutableSet<String>, var at: Long) {
        /** The routes among them the player was refused, each with the visitorData the app had then: see [refused]. */
        val refused = HashMap<String, String>()
    }

    /** Oldest mark first, so the song marked longest ago is the one pushed out. */
    private val songs = LinkedHashMap<String, Marks>()

    /**
     * [route] failed for [id] at [now]. A null route, a song with no known client, marks nothing.
     * [refusedUnder]: the player was refused the route's address, played unchecked, while the
     * app had that visitorData ("" for none): see [refused].
     */
    @Synchronized
    fun mark(id: String, route: String?, now: Long, refusedUnder: String? = null) {
        if (route == null) return
        val marks = songs.remove(id)?.takeIf { now - it.at < keepMs } ?: Marks(mutableSetOf(), now)
        marks.routes += route
        if (refusedUnder != null) marks.refused[route] = refusedUnder
        marks.at = now
        songs[id] = marks
        while (songs.size > most) songs.remove(songs.keys.first())
    }

    /** The routes to ask last for [id], none once [keepMs] has passed since the last mark. */
    @Synchronized
    fun avoid(id: String, now: Long): Set<String> =
        songs[id]?.takeIf { now - it.at in 0 until keepMs }?.routes?.toSet() ?: emptySet()

    /**
     * The routes whose address, played unchecked, the player was refused for [id] while the app
     * had the visitorData [under]. The song's next walk takes VISIONOS's silent check for the
     * same refusal, so that a new visitorData is tried (issue #17); under another visitorData
     * it is a new question.
     */
    @Synchronized
    fun refused(id: String, now: Long, under: String): Set<String> =
        songs[id]?.takeIf { now - it.at in 0 until keepMs }?.refused?.filterValues { it == under }?.keys?.toSet() ?: emptySet()

    companion object {
        const val KEEP_MS = 30 * 60 * 1000L
        const val MOST = 200
    }
}
