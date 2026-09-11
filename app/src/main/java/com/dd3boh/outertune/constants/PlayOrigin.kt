/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

/**
 * Where a play was started from. Stored by code, never by ordinal, so reordering this enum can
 * never silently relabel history.
 *
 * The strength of a signal depends on this: a song searched for and then played is the clearest
 * statement of intent a listener makes, a song that autoplayed sixth in a radio queue is barely a
 * statement at all. Until now the app knew this at the moment of play and threw it away.
 */
enum class PlayOrigin(val code: Int) {
    UNKNOWN(0),
    SEARCH(1),
    QUICK_PICKS(2),
    HOME_ROW(3),
    PLAYLIST(4),
    ALBUM(5),
    ARTIST(6),
    LIBRARY(7),
    RADIO(8),
    HISTORY(9),
    STATS(10),
    QUEUE(11),
    LOCAL_FILES(12),
    MENU(13);

    companion object {
        fun fromCode(code: Int): PlayOrigin = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * How a play ended. Same rule: by code.
 *
 * Natural end and skip are the two that matter most, because a skip in the middle of a song is
 * the one negative signal a listener gives without meaning to, and the play log never held it.
 */
object EndReason {
    const val UNKNOWN = 0
    /** Played to the end, or repeated. */
    const val ENDED = 1
    /** The listener moved to another song before the end. */
    const val SKIPPED = 2
    /** The queue was replaced under it. */
    const val REPLACED = 3
    /** Playback stopped: paused and never resumed, the app closed, the service released. */
    const val STOPPED = 4
}
