/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest whose phone cannot play the host's song (issue #187: YouTube refuses it to him on every
 * client) stays on it, not playing, and tries once more a little later, never every second. It
 * used to skip to the next song, the room sent it back, it failed again, 287 times in a run.
 */
class GuestRefusedSongTest {

    /** A guest whose loads of [bad] always fail, 10 minutes of the room's 250 ms looks. Returns the tries it made. */
    private fun run(bad: String, hostSong: (Long) -> String, minutes: Int = 10): Pair<Int, Boolean> {
        val refusal = HostSongRefusal()
        var tries = 0
        var movedOff = false
        var now = 0L
        var loading = true // the first load of the host's song is asked for at once
        while (now < minutes * 60_000L) {
            val song = hostSong(now)
            refusal.songIs(song)
            if (loading || refusal.retryDue(song, now)) {
                tries++
                loading = false
                if (song == bad) {
                    refusal.failed(song, now)
                }
            }
            // Never a move off the host's song while it is the refused one.
            if (song == bad && !refusal.staysOn(song)) movedOff = true
            now += 250
        }
        return tries to movedOff
    }

    @Test
    fun `a guest whose loads fail makes at most two tries on the host's song and never leaves it`() {
        val (tries, movedOff) = run("bad", { "bad" })
        assertTrue("tries: $tries", tries <= 2)
        assertFalse(movedOff)
    }

    @Test
    fun `the one more try comes a while later, not at once`() {
        val refusal = HostSongRefusal()
        refusal.songIs("bad")
        refusal.failed("bad", 1_000)
        assertFalse(refusal.retryDue("bad", 1_000 + 1_000))
        assertFalse(refusal.retryDue("bad", 1_000 + HostSongRefusal.RETRY_AFTER_MS - 1))
        assertTrue(refusal.retryDue("bad", 1_000 + HostSongRefusal.RETRY_AFTER_MS))
    }

    @Test
    fun `after the second failure nothing is tried again on that song`() {
        val refusal = HostSongRefusal()
        refusal.songIs("bad")
        refusal.failed("bad", 0)
        refusal.failed("bad", HostSongRefusal.RETRY_AFTER_MS)
        assertFalse(refusal.retryDue("bad", 10 * 60_000L))
        assertTrue(refusal.staysOn("bad"))
    }

    @Test
    fun `at the host's next song the guest picks up again by itself`() {
        // The host plays the bad song for the first 3 minutes, then a good one.
        val refusal = HostSongRefusal()
        refusal.songIs("bad")
        refusal.failed("bad", 0)
        refusal.failed("bad", HostSongRefusal.RETRY_AFTER_MS)
        assertTrue(refusal.staysOn("bad"))
        refusal.songIs("good")
        assertFalse(refusal.staysOn("good"))
        assertNull(refusal.refusedId)
        // And the bad song's tries start afresh if it comes round again later.
        refusal.songIs("bad")
        refusal.failed("bad", 400_000)
        assertTrue(refusal.retryDue("bad", 400_000 + HostSongRefusal.RETRY_AFTER_MS))
    }

    @Test
    fun `a failure on another song than the host's is not the room's business`() {
        val refusal = HostSongRefusal()
        refusal.songIs("host")
        refusal.failed("other", 0)
        assertFalse(refusal.staysOn("other"))
        assertNull(refusal.refusedId)
    }

    @Test
    fun `the service leaves a guest's failed song in place and the session holds it`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        // The two ways a failed song was left (gone-skip, skip or stop) ask the room first; the third match is the function.
        assertEquals(3, Regex("holdInRoom\\(").findAll(service).count())
        assertTrue(session.contains("fun holdsFailed("))
        assertTrue(session.contains("HostSongRefusal"))
        // The language of the line is in both files.
        for (dir in listOf("values", "values-fr")) {
            assertTrue(File("src/main/res/$dir/strings-ot.xml").readText().contains("together_song_unavailable"))
        }
    }
}
