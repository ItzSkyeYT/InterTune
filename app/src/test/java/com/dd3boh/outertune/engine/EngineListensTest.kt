/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which listens the engine reads: a play that failed teaches it nothing, anywhere. */
class EngineListensTest {
    private val now = 1_789_135_200_000L
    private val hour = 3_600_000L

    private fun listen(song: String, endReason: Int, playedMs: Long = 100_000, depth: Int = 0, learn: Boolean = true) =
        ListenRow(song, now - 2 * hour, now - 2 * hour + playedMs, playedMs, 200_000, endReason, PlayOrigin.SEARCH.code, depth, 1, 0, learn)

    private val finished = listen("finished", EndReason.ENDED, playedMs = 200_000)
    private val skipped = listen("skipped", EndReason.SKIPPED)
    private val stopped = listen("stopped", EndReason.STOPPED)
    private val replaced = listen("replaced", EndReason.REPLACED)
    private val legacy = listen("legacy", EndReason.UNKNOWN)
    private val failed = listen("failed", EndReason.ERROR, playedMs = 190_000)
    private val playing = listen("playing", EndReason.OPEN).copy(endedAt = 0)

    @Test
    fun `a build reads every play but the failed ones, and the one playing ends now`() {
        val read = EngineListens.forBuild(listOf(finished, skipped, stopped, replaced, legacy, failed, playing), now)
        assertEquals(listOf("finished", "skipped", "stopped", "replaced", "legacy", "playing"), read.map { it.songId })
        assertEquals(now, read.single { it.songId == "playing" }.endedAt)
        // Everything else exactly as stored: a real skip still counts against its song.
        assertEquals(listOf(finished, skipped, stopped, replaced, legacy), read.dropLast(1))
        assertEquals(true, Signals.skip(read.single { it.songId == "skipped" }) > 0.0)
    }

    @Test
    fun `grading sees a failed play, so its card can be settled rather than ignored`() {
        val read = EngineListens.forGrading(listOf(finished, failed, playing), now)
        assertEquals(listOf(finished, failed, playing.copy(endedAt = now)), read)
    }

    @Test
    fun `a failed play is never one of the listener's picks a row is scored on`() {
        val autoplayed = listen("autoplayed", EndReason.ENDED, playedMs = 200_000, depth = 3)
        val forgotten = listen("forgotten", EndReason.ENDED, playedMs = 200_000, learn = false)
        val picks = EngineListens.picks(listOf(finished, skipped, failed, playing, autoplayed, forgotten))
        assertEquals(listOf(finished, skipped), picks)
    }

    @Test
    fun `a failed play heard nearly through is read as nothing, not as a song heard well`() {
        // By its share alone it would count as heard well; as a failure it is not read at all.
        assertEquals(true, Signals.engagement(failed, null) >= EngineParams.DEFAULT.justPlayedEngagement)
        assertEquals(emptyList<ListenRow>(), EngineListens.forBuild(listOf(failed), now))
        assertEquals(emptyList<ListenRow>(), EngineListens.picks(listOf(failed)))
    }
}
