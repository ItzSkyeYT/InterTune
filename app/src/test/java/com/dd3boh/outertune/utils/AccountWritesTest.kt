/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SyncMode
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Read only" says the app never changes anything in the YouTube Music account. It used to be
 * honoured in three screens and nowhere else: likes, saves, subscriptions, renames and deletes all
 * went out whatever the setting said.
 */
class AccountWritesTest {

    @After
    fun restoreGate() {
        YouTube.accountWritesAllowed = { true }
    }

    @Test
    fun `only a signed-in account in read and write may be changed`() {
        assertTrue(mayPushToYouTube(loggedIn = true, mode = SyncMode.RW))
        assertFalse(mayPushToYouTube(loggedIn = true, mode = SyncMode.RO))
        assertFalse(mayPushToYouTube(loggedIn = false, mode = SyncMode.RW))
        assertFalse(mayPushToYouTube(loggedIn = false, mode = SyncMode.RO))
    }

    @Test
    fun `without a running app nothing may be pushed`() {
        // The entities' toggles ask this. In a unit test there is no App, and the answer has to
        // be no rather than a crash or a request.
        assertFalse(mayPushToYouTube())
    }

    @Test
    fun `every account write is refused before any request when the gate says no`(): Unit = runBlocking {
        YouTube.accountWritesAllowed = { false }

        // Each of these would need the network and a signed-in account to succeed. Refused, they
        // fail at once with the gate's own error, which proves no request was attempted.
        val results = listOf(
            YouTube.likeVideo("dQw4w9WgXcQ", true),
            YouTube.likeVideo("dQw4w9WgXcQ", false),
            YouTube.likePlaylist("PLx", true),
            YouTube.subscribeChannel("UCx", true),
            YouTube.addToPlaylist("PLx", "dQw4w9WgXcQ"),
            YouTube.addPlaylistToPlaylist("PLx", "PLy"),
            YouTube.removeFromPlaylist("PLx", "dQw4w9WgXcQ", "set"),
            YouTube.moveSongPlaylist("PLx", "set", "next"),
            YouTube.renamePlaylist("PLx", "name"),
            YouTube.deletePlaylist("PLx"),
        )
        results.forEach { result ->
            assertTrue(result.exceptionOrNull() is YouTube.AccountWriteRefused)
        }
        assertThrows(YouTube.AccountWriteRefused::class.java) {
            YouTube.createPlaylist("name")
        }
    }
}
