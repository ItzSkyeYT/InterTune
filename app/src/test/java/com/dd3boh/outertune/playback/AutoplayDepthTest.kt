/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How far the listener was carried, and what counts as them choosing instead. */
class AutoplayDepthTest {

    private val seek = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    private val auto = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    private val repeat = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
    private val playlist = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

    @Test
    fun `previous and next arrive as the same seek and are told apart by the index`() {
        assertTrue(AutoplayDepth.wentBack(seek, lastIndex = 7, newIndex = 6))
        assertFalse(AutoplayDepth.wentBack(seek, lastIndex = 6, newIndex = 7))
    }

    @Test
    fun `only a seek counts as going back`() {
        // An album ending and rolling into the next queue item is not the listener reaching back.
        assertFalse(AutoplayDepth.wentBack(auto, lastIndex = 7, newIndex = 6))
        assertFalse(AutoplayDepth.wentBack(playlist, lastIndex = 7, newIndex = 0))
    }

    @Test
    fun `with no index yet, nothing is assumed`() {
        assertFalse(AutoplayDepth.wentBack(seek, lastIndex = -1, newIndex = 0))
    }

    @Test
    fun `going back resets the depth, which is the bug this exists for`() {
        // Six deep in a radio, the listener presses previous. That play is a choice, not a
        // seventh helping of whatever the radio decided.
        val back = AutoplayDepth.wentBack(seek, lastIndex = 6, newIndex = 5)
        assertEquals(0, AutoplayDepth.next(current = 6, chosen = false, wentBack = back, reason = seek))
    }

    @Test
    fun `the next button still counts as being carried`() {
        val back = AutoplayDepth.wentBack(seek, lastIndex = 5, newIndex = 6)
        assertEquals(7, AutoplayDepth.next(current = 6, chosen = false, wentBack = back, reason = seek))
    }

    @Test
    fun `a new queue or a tap resets, autoplay grows, a repeat holds`() {
        assertEquals(0, AutoplayDepth.next(current = 9, chosen = true, wentBack = false, reason = playlist))
        assertEquals(0, AutoplayDepth.next(current = 9, chosen = false, wentBack = false, reason = playlist))
        assertEquals(4, AutoplayDepth.next(current = 3, chosen = false, wentBack = false, reason = auto))
        assertEquals(3, AutoplayDepth.next(current = 3, chosen = false, wentBack = false, reason = repeat))
    }
}
