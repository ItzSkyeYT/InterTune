/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Overwrite with remote" removes whatever the remote list lacks. On a 429, a timeout or a
 * signed-out answer that list was empty or cut short, and the whole library went with it.
 */
class SyncRulesTest {

    @Test
    fun `only a complete read with something in it may remove`() {
        assertTrue(mayRemoveMissing(complete = true, remoteCount = 120))
        assertFalse(mayRemoveMissing(complete = false, remoteCount = 120))
        assertFalse(mayRemoveMissing(complete = true, remoteCount = 0))
        assertFalse(mayRemoveMissing(complete = false, remoteCount = 0))
    }

    @Test
    fun `a playlist is replaced only by a complete read`() {
        assertTrue(mayReplacePlaylist(complete = true, remoteCount = 40, hasLocalSongs = true))
        assertFalse(mayReplacePlaylist(complete = false, remoteCount = 40, hasLocalSongs = true))
        assertFalse(mayReplacePlaylist(complete = false, remoteCount = 40, hasLocalSongs = false))
    }

    @Test
    fun `an empty read never empties a playlist that has songs`() {
        assertFalse(mayReplacePlaylist(complete = true, remoteCount = 0, hasLocalSongs = true))
        // Nothing here and nothing there: copying nothing over nothing is harmless.
        assertTrue(mayReplacePlaylist(complete = true, remoteCount = 0, hasLocalSongs = false))
    }
}
