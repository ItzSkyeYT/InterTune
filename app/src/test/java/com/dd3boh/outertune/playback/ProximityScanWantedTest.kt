/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Quieter as you walk away scans: through the seeks, skips and rebuffers that only interrupt
 * the sound for a moment, and not once playback is paused, finished or stopped by an error.
 */
class ProximityScanWantedTest {

    private fun scans(playWhenReady: Boolean, playbackState: Int, enabled: Boolean = true) =
        ProximityVolume.shouldScan(enabled, playWhenReady, playbackState)

    @Test
    fun `playing scans`() {
        assertTrue(scans(playWhenReady = true, playbackState = Player.STATE_READY))
    }

    @Test
    fun `a seek or a skip, masked to buffering, keeps the scan running`() {
        assertTrue(scans(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun `a pause stops the scan, also while buffering`() {
        assertFalse(scans(playWhenReady = false, playbackState = Player.STATE_READY))
        assertFalse(scans(playWhenReady = false, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun `the end of the queue stops the scan although playWhenReady stays set`() {
        assertFalse(scans(playWhenReady = true, playbackState = Player.STATE_ENDED))
    }

    @Test
    fun `an error waiting on the network leaves the player idle and stops the scan`() {
        // A fatal error goes to idle without touching playWhenReady, and the network wait keeps it
        // there between retries.
        assertFalse(scans(playWhenReady = true, playbackState = Player.STATE_IDLE))
    }

    @Test
    fun `with the setting off it never scans`() {
        val states = listOf(
            Player.STATE_IDLE,
            Player.STATE_BUFFERING,
            Player.STATE_READY,
            Player.STATE_ENDED,
        )
        for (state in states) {
            assertFalse(scans(playWhenReady = true, playbackState = state, enabled = false))
            assertFalse(scans(playWhenReady = false, playbackState = state, enabled = false))
        }
    }
}
