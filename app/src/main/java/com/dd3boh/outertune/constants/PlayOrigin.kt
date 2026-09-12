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
    MENU(13),
    /** Playback resumed by the system after a restart, through onPlaybackResumption. */
    RESUMED(14),
    /** Set by another app or a controller, through onSetMediaItems. */
    EXTERNAL(15),
    /** A song the listener identified by ear, which is as deliberate as a search. */
    RECOGNISED(16),
    /** A card tapped on the home screen widget: the Quick picks row, outside the app. */
    WIDGET(17);

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
    /** Playback failed. */
    const val ERROR = 5
    /** Still playing, or the app died before this row was closed. Closed as STOPPED on the next launch. */
    const val OPEN = 6
}

/** Things the listener did during or about a song, beyond playing it. Stored by code. */
object SignalKind {
    const val SEEK_BACK = 1
    const val SEEK_FORWARD = 2
    const val REPEAT_ONE_ON = 3
    const val REPEAT_ONE_OFF = 4
    const val VOLUME_UP = 5
    const val VOLUME_DOWN = 6
    const val ADD_TO_PLAYLIST = 7
    const val DOWNLOAD = 8
    const val SHARE = 9
    const val LYRICS = 10
    const val ARTIST_PAGE = 11
    const val REMOVED_FROM_QUEUE = 12
    const val CARD_DISMISSED = 13
}
