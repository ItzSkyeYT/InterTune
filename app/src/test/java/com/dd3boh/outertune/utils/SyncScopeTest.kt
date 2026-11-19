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
 * SyncUtils launches syncPlaylistDetached on its own scope, where nobody waits for it. What it throws
 * (syncPlaylist's direct read of the playlist, once a restore has closed the database, or a damaged
 * one) had nowhere to go on a scope with no handler, and ended the app. The scope logs it instead, as
 * QueueBoard's save scope does (PlaybackFaultsTest). likeSong goes out with the song's other ratings,
 * in the order they were tapped (RatingWrites, #171), whose queue logs a write that throws and runs
 * the next one (RatingWritesTest).
 *
 * Read from the source: what goes wrong takes a broken database to show.
 */
class SyncScopeTest {
    private val source = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()

    private fun function(name: String): String =
        source.substringAfter("fun $name(", "").substringBefore("\n    }\n").also { assertTrue("$name is there", it.isNotEmpty()) }

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
    fun `the jobs nobody waits for log what they throw instead of ending the app`() {
        assertTrue("syncPlaylistDetached launches on the scope with the handler", "scope.launch {" in function("syncPlaylistDetached"))
        // A like is sent in tap order with the song's other ratings, on the ratings' own queue.
        assertTrue("likeSong goes through the ratings' queue", "RatingWrites.send(s.id) {" in function("likeSong"))
        val ratings = File("src/main/java/com/dd3boh/outertune/utils/RatingWrites.kt").readText()
        assertTrue("the ratings' queue catches a write that throws", Regex("""catch \(e: Exception\) \{\s*onError\(e\)""").containsMatchIn(ratings))
        assertTrue("and says so in the log", "Log.w(\"RatingWrites\", \"A rating for YouTube Music could not be sent\", it)" in ratings)
    }
}
