/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * Every time limit on the way from "play" to sound, in one place, so that they can be read
 * together and checked against each other (RouteTimesTest). No other file writes a playback time
 * limit as a number.
 *
 * Why these numbers: on 20 Oct 2026 /player answered in about 0.2 s, a fresh connection answered
 * at once, and a connection that did not answer never did. Each limit used to be generous on its
 * own, 30 s for /player and 10 s a check, and they added up to a minute of silence while the
 * chain walked from client to client. So a step that has not answered by a few times its usual
 * time is given up, or tried again on a new connection, and only an answer from YouTube is taken
 * for a judgement on a client.
 *
 * The variables are the same numbers, shortened by the tests that run out of them on purpose.
 */
object RouteTimes {
    /** /player: a second try of the same client on a fresh connection, at 7 times its usual time. */
    const val PLAYER_FRESH_MS = 1_500L

    /** /player: the next client asked alongside, the fresh try having had its own 1.5 s by then. */
    const val PLAYER_ALONGSIDE_MS = 3_000L

    /**
     * /player: one client given up, both tries together. An answer is 15 to 25 KB gzipped, 2 to
     * 4 s on a bad 2G link with a new TLS handshake.
     */
    const val PLAYER_CAP_MS = 5_000L

    /** /player for the last client of a walk, and for every client once two in a row ran out: a slow network still works. */
    const val PLAYER_SLOW_CAP_MS = 10_000L

    /** Clients in a row that ran out of [PLAYER_CAP_MS] before the walk takes the network for slow. */
    const val SLOW_AFTER = 2

    /** How long a network is taken for slow after it was found so or last answered slowly, and how far back answer times are looked at: see NetworkPace. */
    const val SLOW_REMEMBER_MS = 5 * 60 * 1000L

    /** After a /player request on a kept connection did not answer, how long every /player goes out on a new one. */
    const val POOL_SUSPECT_MS = 30_000L

    /** The stream check: a second try on a fresh connection. */
    const val HEAD_FRESH_MS = 1_500L

    /** The stream check: how long the walk waits for it before going on without its answer. */
    const val HEAD_WAIT_MS = 3_000L

    /** The stream check: how long it keeps running after the walk went on, for a late refusal. */
    const val HEAD_LATE_MS = 10_000L

    /** One try of the stream check: connect, read and the whole call. */
    const val HEAD_TRY_MS = 3_000L

    /**
     * One try of the stream check while the network is taken for slow, for the clients that never
     * play unchecked, the web client and the account's on it. A HEAD on a new connection is four
     * or five round trips, past [HEAD_TRY_MS] above 0.7 s each, and their check then never passed:
     * a song only the account may play stopped on such a link, where 0.12 waited 10 s for it.
     */
    const val HEAD_SLOW_TRY_MS = 8_000L

    /** NewPipe's n and signature step: how long the walk waits for it. Its first fetch is a 0.7 MB script. */
    const val DECIPHER_WAIT_MS = 8_000L

    /** NewPipe's own client: connect, silence in a read, and how long a connection may sit idle. */
    const val NEWPIPE_CONNECT_MS = 5_000L
    const val NEWPIPE_READ_MS = 6_000L
    const val IDLE_MS = 5_000L

    /**
     * NewPipe's own client: one whole fetch. Its player script is about 0.7 MB, which 15 s cut
     * below about 400 kbit/s, every time; this lets it through down to about 30 kbit/s. A fetch
     * that stops is ended by [NEWPIPE_READ_MS] long before.
     */
    const val NEWPIPE_CALL_MS = 180_000L

    /** A web client's po tokens: how long the walk waits; the WebView keeps its own 20 s for the next song. */
    const val PO_TOKEN_WAIT_MS = 8_000L

    /**
     * The whole resolve of one song, every pass, family and stand-in: about four dead routes at
     * the numbers above. Past that the network is the problem, and a clean try a second later
     * beats walking on. The ad waits of a web client's address are added to it, not counted.
     */
    const val RESOLVE_MS = 20_000L

    /** The player: no byte for this long while it buffers and wants to play is a stall. */
    const val STALL_MS = 6_000L

    /**
     * The player: how long a connection that has brought no byte yet may wait for its first,
     * before the stall watch takes its next step. Making one is four or five round trips (DNS,
     * TCP, TLS, the request), 7 to 9 s where each takes 1.5 to 2 s, and [STALL_MS] counted from
     * the start cut every connection mid-way on such a link. The stream client's own limits, 5 s
     * to connect and 8 s for a silent read, end a dead one before this.
     */
    const val FIRST_BYTE_MS = 15_000L

    /** The player: a second failed load of a song within this changes its route. */
    const val LOAD_FAILURE_WINDOW_MS = 30_000L

    /** The player: a stopped song is prepared again quietly after this, the first time and after. */
    const val QUIET_RETRY_FIRST_MS = 1_000L
    const val QUIET_RETRY_LATER_MS = 3_000L

    /** Recoveries: per song and in all, within [RECOVERY_WINDOW_MS]. Well inside Android's 10 minute foreground window. */
    const val RECOVERIES_PER_SONG = 3
    const val RECOVERIES_IN_ALL = 8
    const val RECOVERY_WINDOW_MS = 10 * 60 * 1000L

    /**
     * A 403 within this of a change of the network in use is the new public address's, not the
     * route's: the address named the old one. No strike.
     */
    const val NETWORK_CHANGE_GRACE_MS = 60_000L

    /**
     * A kept address is not handed out in its last stretch: its end is counted from the end of
     * the walk, up to 20 s after YouTube's answer, and an open there got 403 and struck a
     * healthy route.
     */
    const val ADDRESS_MARGIN_MS = 90_000L

    /**
     * SABR: silence in a read of one of its answers. Each request is answered at once with the
     * next stretch of the song, and the library waits for the player between requests, never
     * inside one, so this is the stream client's own 8 s. It had 30 s. To be seen on a phone.
     */
    const val SABR_READ_MS = 8_000L

    /** Second tries and clients asked alongside: at most this many a minute, and per song. */
    const val HEDGES_PER_MINUTE = 4
    const val EXTRA_PLAYER_PER_SONG = 2
    const val EXTRA_HEAD_PER_SONG = 1

    @Volatile internal var playerFreshMs = PLAYER_FRESH_MS
    @Volatile internal var playerAlongsideMs = PLAYER_ALONGSIDE_MS
    @Volatile internal var playerCapMs = PLAYER_CAP_MS
    @Volatile internal var playerSlowCapMs = PLAYER_SLOW_CAP_MS
    @Volatile internal var headFreshMs = HEAD_FRESH_MS
    @Volatile internal var headWaitMs = HEAD_WAIT_MS
    @Volatile internal var headLateMs = HEAD_LATE_MS
    @Volatile internal var decipherWaitMs = DECIPHER_WAIT_MS
    @Volatile internal var poTokenWaitMs = PO_TOKEN_WAIT_MS
    @Volatile internal var resolveMs = RESOLVE_MS

    /** For the tests, each of which starts as a new process does. */
    internal fun reset() {
        playerFreshMs = PLAYER_FRESH_MS
        playerAlongsideMs = PLAYER_ALONGSIDE_MS
        playerCapMs = PLAYER_CAP_MS
        playerSlowCapMs = PLAYER_SLOW_CAP_MS
        headFreshMs = HEAD_FRESH_MS
        headWaitMs = HEAD_WAIT_MS
        headLateMs = HEAD_LATE_MS
        decipherWaitMs = DECIPHER_WAIT_MS
        poTokenWaitMs = PO_TOKEN_WAIT_MS
        resolveMs = RESOLVE_MS
    }
}
