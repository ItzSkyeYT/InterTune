/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SyncUtils launches likeSong and syncPlaylistDetached on its own scope, where nobody waits for
 * them. What they throw (syncPlaylist's direct read of the playlist, once a restore has closed the
 * database, or a damaged one) had nowhere to go on a scope with no handler, and ended the app. The
 * scope logs it instead, as QueueBoard's save scope does (PlaybackFaultsTest).
 *
 * Read from the source: what goes wrong takes a broken database to show.
 */
class SyncScopeTest {
    private val source = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()

    @Test
    fun `the sync's own scope logs a job that fails instead of ending the app`() {
        val scope = source.lines().single { "private val scope = CoroutineScope(" in it }
        assertTrue(scope, "SupervisorJob()" in scope)
        assertTrue(scope, "syncFailed" in scope)
        val handler = source.substringAfter("val syncFailed = ", "").substringBefore("\n")
        assertTrue("syncFailed is a CoroutineExceptionHandler", handler.startsWith("CoroutineExceptionHandler {"))
        assertTrue("syncFailed says so in the log, where Report a problem finds it", "Log.e(" in handler)
    }

    @Test
    fun `the jobs nobody waits for are launched on that scope`() {
        for (name in listOf("likeSong", "syncPlaylistDetached")) {
            val fn = source.substringAfter("fun $name(", "").substringBefore("\n    }\n")
            assertTrue("$name is there", fn.isNotEmpty())
            assertTrue("$name launches on the scope with the handler", "scope.launch {" in fn)
        }
    }
}
