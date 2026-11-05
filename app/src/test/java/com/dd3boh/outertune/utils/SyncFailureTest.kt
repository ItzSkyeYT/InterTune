/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A write that fails during a sync ends that kind of sync, logged, and not the app (4 Nov 2026).
 * Each kind had a try and a finally only, and the sync at launch runs on a scope with nothing to
 * catch it: on a full phone the liked songs' transaction threw SQLiteFullException, the time
 * noted in the finally threw IOException, and the app closed a few seconds after opening. With
 * the time never noted, the sync was due again at the next opening, and so on.
 *
 * Read from the source, as SyncBusyTest does: what goes wrong takes a full phone to show.
 */
class SyncFailureTest {
    private val source = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()

    /** Each kind, by its function, and the key its cooldown is noted under. */
    private val kinds = mapOf(
        "likedSongs" to "LastLikeSongSyncKey",
        "librarySongs" to "LastLibSongSyncKey",
        "albums" to "LastAlbumSyncKey",
        "artists" to "LastArtistSyncKey",
        "playlists" to "LastPlaylistSyncKey",
        "recentActivity" to "LastRecentActivitySyncKey",
    )

    /** The body of the function declared by [declaration], braces matched. */
    private fun body(declaration: String): String {
        val start = source.indexOf(declaration)
        assertTrue("$declaration is gone: look at how the sync is built now", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        return source.substring(open)
    }

    private val caught = Regex(
        """\}\s*catch \(e: CancellationException\) \{\s*throw e\s*\}\s*catch \(e: Exception\) \{\s*result = failed\("[^"]+", e\)\s*\}\s*finally \{"""
    )

    @Test
    fun `each kind catches what its work throws, lets cancellation through, and still notes its time`() {
        for ((kind, key) in kinds) {
            val body = body("private suspend fun $kind(")
            val match = caught.find(body)
            assertTrue("$kind has no catch before its finally", match != null)
            val finally = body.substring(match!!.range.last)
            assertTrue("$kind notes its time through stamp()", "withContext(NonCancellable) { stamp($key) }" in finally)
        }
    }

    @Test
    fun `the time is noted by one function, and a write that fails there is logged, not thrown`() {
        assertEquals("every dataStore.edit in the sync is the one in stamp()", 1, Regex("""dataStore\.edit\b""").findAll(source).count())
        val stamp = body("private suspend fun stamp(")
        assertTrue("dataStore.edit" in stamp)
        assertTrue(Regex("""catch \(e: CancellationException\) \{\s*throw e\s*\}\s*catch \(e: Exception\) \{\s*Log\.w""").containsMatchIn(stamp))
    }

    @Test
    fun `the sync at launch notes its own time the same way, after every kind has run`() {
        val autoSync = body("private suspend fun autoSync(")
        val kinds = autoSync.indexOf("syncRemotePlaylists(bypassCd)")
        assertTrue(kinds > 0)
        assertTrue(autoSync.indexOf("stamp(LastFullSyncKey)") > kinds)
    }

    @Test
    fun `a failed kind says so, as a sync that did not finish`() {
        val failed = body("private fun failed(")
        assertTrue("Log.e(TAG" in failed)
        assertTrue("return SyncResult.FAILED" in failed)
    }
}
