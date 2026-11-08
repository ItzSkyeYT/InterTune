/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** When the player passes over a song the listener chose not to play (DontPlaySkip), and how MusicService asks. */
class DontPlaySkipTest {

    private fun passes(reason: Int, chosen: Boolean = false, byHand: Boolean = false, casting: Boolean = false) =
        DontPlaySkip.passes(reason, chosen, byHand, casting)

    @Test
    fun `where the player came to it by itself it is passed over`() {
        // The song before ended, or next or previous was pressed.
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK))
        // A receiver plays on by itself too.
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO, casting = true))
    }

    @Test
    fun `a song the listener picked plays`() {
        // The song a queue starts on: tapped in a list, a radio started from it, a link.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
        // Tapped in the queue sheet.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, chosen = true))
        // Put in by hand, with Play next or Add to queue, however the player comes to it.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO, byHand = true))
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, byHand = true))
        // The same song again under repeat one.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        // While casting no tap is known, so a move by hand may be one.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, casting = true))
    }

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `MusicService asks at every song change, beside a blocked artist's song, and at once for the song playing`() {
        assertTrue("val dontPlay = DontPlaySkip.passes(reason, chosen = chosen, byHand = id in heard.byHand, casting = isCasting)" in service)
        assertTrue("runCatching { passOverBlocked(back, dontPlay = dontPlay) }" in service)
        // Set not to play while it plays: passed over at once, the menu having said it all.
        assertTrue("runCatching { passOverBlocked(dontPlay = true, asked = true) }" in service)
        // Where it goes is never another song not to play, unless that one was put in by hand.
        assertTrue("block.hides(it) || (songs.covers(it) && it.id !in byHand)" in service)
        // Play pressed on one the music stopped on picks it.
        assertTrue("playWhenReady && (!ContentFilter.current.blocked.isEmpty || stoppedOnBlocked)" in service)
        // Each says why in its own words.
        assertEquals(1, service.split("R.string.dont_play_skipped").size - 1)
        assertTrue("if (blockedHere) R.string.blocked_songs_left else R.string.dont_play_songs_left" in service)
    }
}
