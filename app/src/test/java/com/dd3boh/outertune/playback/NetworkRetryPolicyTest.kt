/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that decide how long a stalled song waits, and when the waiting stops being true. */
class NetworkRetryPolicyTest {

    @Test
    fun `the wait is bounded, so nothing is held for ever`() {
        assertTrue(NetworkRetryPolicy.shouldRetry(1))
        assertTrue(NetworkRetryPolicy.shouldRetry(NetworkRetryPolicy.MAX_ATTEMPTS))
        assertFalse(NetworkRetryPolicy.shouldRetry(NetworkRetryPolicy.MAX_ATTEMPTS + 1))
        assertFalse(NetworkRetryPolicy.shouldRetry(0))
        // A minute at the outside: long enough for a blip, short enough that a phone genuinely
        // offline is handed back to the connectivity observer while the listener is still there.
        assertTrue(NetworkRetryPolicy.totalWaitMillis() in 10_000..60_000)
    }

    @Test
    fun `the first try is quick and the ladder is capped`() {
        assertEquals(2_000L, NetworkRetryPolicy.delayMillis(1))
        assertEquals(4_000L, NetworkRetryPolicy.delayMillis(2))
        val delays = (1..NetworkRetryPolicy.MAX_ATTEMPTS).map { NetworkRetryPolicy.delayMillis(it) }
        assertEquals(delays.sorted(), delays)
        assertTrue(delays.all { it <= 20_000L })
    }

    @Test
    fun `an error leaves the player idle with playWhenReady still set, and that is what waiting means`() {
        // The state the bug lived in: a fatal error goes to idle without touching playWhenReady,
        // so the old rule of clearing on a pause never fired.
        assertTrue(NetworkRetryPolicy.stillWaiting(true, Player.STATE_IDLE, playWhenReady = true))
        assertTrue(NetworkRetryPolicy.stillWaiting(true, Player.STATE_BUFFERING, playWhenReady = true))
    }

    @Test
    fun `the wait ends when the music comes back, or when the listener pauses`() {
        assertFalse(NetworkRetryPolicy.stillWaiting(true, Player.STATE_READY, playWhenReady = true))
        assertFalse(NetworkRetryPolicy.stillWaiting(true, Player.STATE_ENDED, playWhenReady = true))
        assertFalse(NetworkRetryPolicy.stillWaiting(true, Player.STATE_IDLE, playWhenReady = false))
    }

    @Test
    fun `nothing is waiting when the flag was never set`() {
        assertFalse(NetworkRetryPolicy.stillWaiting(false, Player.STATE_IDLE, playWhenReady = true))
    }

    @Test
    fun `offline, a local file that has gone does not wait for the network`() {
        // What the resolver throws for a local song whose file is missing, as the player hands it
        // on: an unspecified IO error with the resolver's own exception underneath.
        assertFalse(
            NetworkRetryPolicy.waitsForNetwork(
                offline = true,
                errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                causeCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                localSong = true,
            )
        )
        // A local song never waits, whatever the code says.
        assertFalse(
            NetworkRetryPolicy.waitsForNetwork(true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null, localSong = true)
        )
    }

    @Test
    fun `offline, a download whose file is missing or a file that will not decode does not wait`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { code ->
            assertFalse("$code", NetworkRetryPolicy.waitsForNetwork(true, code, null, localSong = false))
        }
    }

    @Test
    fun `offline, a streamed song that fails waits, whatever the resolver made of it`() {
        // Offline, the player request fails with whatever the HTTP client throws, and anything the
        // resolver does not recognise arrives as a remote error.
        assertTrue(
            NetworkRetryPolicy.waitsForNetwork(
                true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_REMOTE_ERROR, false
            )
        )
        assertTrue(
            NetworkRetryPolicy.waitsForNetwork(true, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null, false)
        )
        assertTrue(
            NetworkRetryPolicy.waitsForNetwork(
                true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, false
            )
        )
    }

    @Test
    fun `online, only the resolver failing to connect waits, as before`() {
        assertTrue(
            NetworkRetryPolicy.waitsForNetwork(
                false, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, false
            )
        )
        assertFalse(
            NetworkRetryPolicy.waitsForNetwork(
                false, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_REMOTE_ERROR, false
            )
        )
        assertFalse(
            NetworkRetryPolicy.waitsForNetwork(false, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null, false)
        )
    }
}
