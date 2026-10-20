/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.utils.YTPlayerUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #28: on one phone every song failed with "This video is not available", which is what
 * YouTube says to a connection it has stopped serving as much as of a song it no longer has.
 * Once StandIns stops believing it, the player says what it is, in the listener's language.
 */
class ConnectionTurnedAwayTest {
    @Test
    fun `the player says the connection is turned away, in English and in French`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val onError = service.substringAfter("override fun onPlayerError(").substringBefore("override fun onPlayWhenReadyChanged(")
        assertTrue(onError.contains("YTPlayerUtils.isConnectionTurnedAway(error)"))
        assertTrue(onError.contains("R.string.error_connection_turned_away"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            assertTrue(path, File(path).readText().contains("<string name=\"error_connection_turned_away\">"))
        }
    }

    @Test
    fun `it is found where the player hands it over, and never taken for a song that is gone`() {
        val turnedAway = YTPlayerUtils.ConnectionTurnedAway("This video is not available")
        // As ExoPlayer hands a loader's failure to onPlayerError: wrapped, a cause or two down.
        val handedOver = PlaybackException("Source error", RuntimeException("Unexpected", turnedAway), PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertTrue(YTPlayerUtils.isConnectionTurnedAway(handedOver))
        assertFalse(YTPlayerUtils.isUnavailableSong(handedOver))
        assertFalse(YTPlayerUtils.isConnectionTurnedAway(YTPlayerUtils.SongUnavailable("This video is not available")))
    }
}
