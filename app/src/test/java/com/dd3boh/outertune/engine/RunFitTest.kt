/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the row follows what is playing now, and stops following it once "now" is over.
 *
 * The rest of the vector is about the listener or about the artist, so a candidate by an artist
 * played often at this hour scores the same whether it is the slowed edit or the phonk. This is
 * the one feature that can tell those apart, and the thing it must not do is keep answering after
 * the run it was reading has ended.
 */
class RunFitTest {

    private val now = 1_789_135_200_000L
    private val minute = 60_000L

    private fun songs(vararg pairs: Pair<String, String>): Map<String, SongRow> =
        pairs.associate { (id, title) -> id to SongRow(id, title, "art_a", "artist", false, null, true) }

    private fun listen(id: String, minutesAgo: Long): ListenRow {
        val start = now - minutesAgo * minute
        return ListenRow(id, start, start + 180_000L, 180_000L, 200_000L, EndReason.ENDED, PlayOrigin.SEARCH.code, 0, 1L, 0)
    }

    private fun contextFor(listens: List<ListenRow>, songs: Map<String, SongRow>): TagFit.Context {
        val input = EngineInput(now, songs, listens, emptyList())
        return Features.Context(
            stats = LibraryStats(input),
            groups = VersionGroups(input.songs.values, input.versionLinks),
            input = input,
            referrers = emptyMap(),
        ).tagContext
    }

    @Test
    fun `a run of slowed edits is recognised`() {
        val s = songs("a" to "One (Slowed)", "b" to "Two (Slowed + Reverb)", "c" to "Three (Slowed)")
        val ctx = contextFor(listOf(listen("a", 5), listen("b", 10), listen("c", 15)), s)

        assertTrue("the run is known", ctx.known)
        assertTrue("it is a treated run", ctx.treatedShare > 0.5)
        // Another slowed edit fits it; a nightcore track does not.
        assertTrue(TagFit.score(SongTags.of("Four (Slowed)"), ctx) > 0.0)
        assertTrue(TagFit.score(SongTags.of("Four (Nightcore)"), ctx) < 0.0)
    }

    @Test
    fun `a plain run welcomes plain songs and not treated ones`() {
        val s = songs("a" to "One", "b" to "Two", "c" to "Three")
        val ctx = contextFor(listOf(listen("a", 5), listen("b", 10), listen("c", 15)), s)

        assertTrue(ctx.known)
        assertEquals(0.0, ctx.treatedShare, 1e-9)
        assertTrue(TagFit.score(SongTags.of("Four"), ctx) > 0.0)
        assertTrue(TagFit.score(SongTags.of("Four (Nightcore)"), ctx) <= 0.0)
    }

    @Test
    fun `an hour and a half later the run is over and nothing is claimed`() {
        // The whole point: moods change when somebody shuts the app and comes back. A run that
        // ended two hours ago must not steer the row that gets built now.
        val s = songs("a" to "One (Slowed)", "b" to "Two (Slowed)")
        val ctx = contextFor(listOf(listen("a", 120), listen("b", 125)), s)

        assertFalse("nothing recent enough to call a run", ctx.known)
        // And with no run, the feature is silent rather than guessing.
        assertEquals(0.0, TagFit.score(SongTags.of("Four (Slowed)"), ctx), 1e-9)
        assertEquals(0.0, TagFit.score(SongTags.of("Four (Nightcore)"), ctx), 1e-9)
    }

    @Test
    fun `an old run does not dilute a fresh one`() {
        val s = songs(
            "old1" to "Old (Nightcore)", "old2" to "Older (Nightcore)",
            "new1" to "New (Slowed)", "new2" to "Newer (Slowed)",
        )
        val ctx = contextFor(
            listOf(listen("new1", 3), listen("new2", 8), listen("old1", 200), listen("old2", 205)),
            s,
        )

        assertTrue(ctx.known)
        assertTrue("the fresh slowed run wins", TagFit.score(SongTags.of("X (Slowed)"), ctx) > 0.0)
        assertTrue("the old nightcore run is gone", TagFit.score(SongTags.of("X (Nightcore)"), ctx) < 0.0)
    }
}
