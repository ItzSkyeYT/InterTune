/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a queue picks up again after the service stops, and after a change of song. */
class ResumePointTest {

    private val fortyMinutes = 40 * 60_000L

    @Test
    fun `a run that never loaded the queue keeps the saved point`() {
        // Paused a mix at 40:00 and swiped away; later the app was opened and left without
        // pressing play, so the player is empty at 0 when the service stops.
        assertEquals(fortyMinutes, ResumePoint.onStop(playerSongId = null, queueSongId = "mix", playerPositionMs = 0, savedPositionMs = fortyMinutes))
    }

    @Test
    fun `a player on another song keeps the saved point`() {
        // The player holds some other list than the queue being saved.
        assertEquals(fortyMinutes, ResumePoint.onStop(playerSongId = "car", queueSongId = "mix", playerPositionMs = 12_000, savedPositionMs = fortyMinutes))
    }

    @Test
    fun `a player holding the queue saves where it is`() {
        assertEquals(41 * 60_000L, ResumePoint.onStop(playerSongId = "mix", queueSongId = "mix", playerPositionMs = 41 * 60_000L, savedPositionMs = fortyMinutes))
    }

    @Test
    fun `a queue with no current song keeps the saved point`() {
        assertEquals(C.TIME_UNSET, ResumePoint.onStop(playerSongId = "mix", queueSongId = null, playerPositionMs = 5_000, savedPositionMs = C.TIME_UNSET))
    }

    @Test
    fun `skipping drops the pause point of the song before`() {
        // Paused A at 2:30, then next: B must not come back at 2:30.
        assertEquals(C.TIME_UNSET, ResumePoint.afterTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, playerPositionMs = 0, savedPositionMs = 150_000))
    }

    @Test
    fun `a song that ends drops the pause point too`() {
        assertEquals(C.TIME_UNSET, ResumePoint.afterTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, playerPositionMs = 3, savedPositionMs = 150_000))
    }

    @Test
    fun `a repeat stays on the same song and keeps its point`() {
        assertEquals(150_000L, ResumePoint.afterTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, playerPositionMs = 0, savedPositionMs = 150_000))
    }

    @Test
    fun `a restored queue keeps the position it was restored at`() {
        // setMediaItems at the saved point: the player already stands there.
        assertEquals(fortyMinutes, ResumePoint.afterTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, playerPositionMs = fortyMinutes, savedPositionMs = fortyMinutes))
    }

    @Test
    fun `a list loaded from the top drops an old point`() {
        // The same queue started again from its first second, at the place it was paused before.
        assertEquals(C.TIME_UNSET, ResumePoint.afterTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, playerPositionMs = 0, savedPositionMs = 150_000))
    }
}
