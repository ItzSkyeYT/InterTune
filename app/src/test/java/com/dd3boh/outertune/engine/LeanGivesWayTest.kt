/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A lean never leaves the listener with less than Auto would. With nothing of its own to give it
 * builds Auto's row, card for card, and it never turns a row long enough to show into one that is
 * not. Over small made-up libraries, since that is where a lean runs out.
 */
class LeanGivesWayTest {
    private val now = 1_789_135_200_000L
    private val day = 86_400_000L
    private val leans = Lean.entries.filter { it != Lean.AUTO }
    private val minCards = EngineParams.DEFAULT.minCards

    private class Library(val now: Long) {
        val songs = LinkedHashMap<String, SongRow>()
        val listens = ArrayList<ListenRow>()
        val edges = ArrayList<Edge>()
        fun song(id: String, artist: String, title: String, likedAt: Long? = null, inLibrary: Boolean = true) {
            songs[id] = SongRow(id, title, "art_$artist", artist, likedAt != null, likedAt, inLibrary)
        }
        fun play(id: String, hoursAgo: Double, session: Long, ratio: Double = 1.0, ended: Int = EndReason.ENDED) {
            val start = now - (hoursAgo * 3_600_000).toLong()
            val played = (200_000L * ratio).toLong()
            listens += ListenRow(id, start, start + played, played, 200_000L, ended, PlayOrigin.SEARCH.code, 0, session, 0)
        }
        fun edge(seed: String, to: String, sources: Int = Provenance.YOUTUBE) { edges += Edge(seed, to, sources) }
        fun input(chip: Int = ContextChip.AUTO) = EngineInput(now, songs, listens, edges, chip = chip)
    }

    /** What is asked of the row beside the lean: a chip, New songs only, Both's share. */
    private class Setting(val name: String, val chip: Int = ContextChip.AUTO, val newOnly: Boolean = false, val share: Double? = null)

    private val settings = listOf(
        Setting("plain"), Setting("discover", chip = ContextChip.DISCOVER), Setting("new songs only", newOnly = true),
        Setting("chill", chip = ContextChip.CHILL), Setting("both", share = 0.5),
    )

    private fun build(w: Library, s: Setting, dice: Int, stored: Lean = Lean.AUTO) =
        EngineRow.build(w.input(s.chip), newOnly = s.newOnly, random = Random(dice.toLong()), lastFmShare = s.share, stored = stored)

    /** Everything a row is and everything recorded with it, so two builds can be held against each other exactly. */
    private fun sig(r: BuiltRow): String = buildString {
        r.cards.forEach { c -> append("${c.songId}|${c.lane}|${c.z}|${c.p}|${c.features.toList()}|${c.reasons}|${c.seedId}|${c.sampled}|${c.sources};") }
        append("#"); r.pool.forEach { c -> append("${c.songId}|${c.lane}|${c.z}|${c.reasons}|${c.seedId}|${c.sources};") }
        append("#${r.seeds}#${r.quotas}#${r.lastFmShare}#${r.lean}#${r.leanPlaced}#${r.leadArtistCap}#${r.leadWeight}")
    }

    private fun cards(r: BuiltRow) = r.cards.map { "${it.songId} ${it.lane}" }

    /**
     * A library where every lean comes up empty: four artists played in two sessions, so none is
     * one the listener keeps coming back to; nothing older than three days, so nothing is
     * forgotten; related lists only for the first session's songs, so nothing fits the last one;
     * and every song the lists name is in the library, so none is one never heard of.
     */
    private fun nothingForAnyLean(): Library {
        val w = Library(now)
        for (a in 0 until 4) for (i in 0 until 6) w.song("p${a}s$i", "played$a", "Played $a $i")
        for (a in 0 until 4) for (i in 0 until 2) w.song("q${a}s$i", "shelf$a", "Shelf $a $i")
        for (a in 0 until 4) w.play("p${a}s0", hoursAgo = 72.0 - a * 0.1, session = 1)
        for (a in 0 until 4) w.play("p${a}s1", hoursAgo = 20.0 - a * 0.1, session = 2)
        for (a in 0 until 4) { w.edge("p${a}s0", "q${a}s0"); w.edge("p${a}s0", "q${(a + 1) % 4}s1"); w.edge("p${a}s0", "p${(a + 1) % 4}s2") }
        return w
    }

    @Test
    fun `a lean whose lane has nothing builds Auto's row, card for card`() {
        val w = nothingForAnyLean()
        var builds = 0
        for (lean in leans) for (s in settings) {
            if (EngineRow.shape(newOnly = s.newOnly, chip = s.chip, stored = lean).lead == null) continue
            repeat(8) { dice ->
                val auto = build(w, s, dice)
                val leaned = build(w, s, dice, lean)
                // A row worth having, so the comparison is not of two empty rows.
                assertTrue("$lean, ${s.name}: Auto has ${auto.cards.size} cards", auto.cards.size >= minCards)
                assertEquals("$lean, ${s.name} #$dice", cards(auto), cards(leaned))
                assertEquals("$lean, ${s.name} #$dice", sig(auto), sig(leaned))
                assertEquals(LeanGaveWay(lean, nothing = true), leaned.gaveWay)
                builds++
            }
        }
        // Every lean with no chip, with a mood where it still applies, with Both, and Never heard with Discover.
        assertEquals(8 * (4 + 3 + 4 + 1), builds)
    }

    /**
     * A small library drawn from [seed]: one to five artists played, two to seven songs each, up
     * to six artists that only related lists know, one to eight sessions over a fortnight or a
     * few months, sometimes one under way, and related lists for about half the songs played.
     * One in four has months behind it instead, four to eight artists and fifteen to forty
     * sessions, which is what it takes for a song to have been loved and forgotten.
     */
    private fun library(seed: Int): Library {
        val r = Random(seed)
        val w = Library(now)
        val months = r.nextInt(4) == 0
        val artists = if (months) 4 + r.nextInt(5) else 1 + r.nextInt(5)
        val each = 2 + r.nextInt(6)
        for (a in 0 until artists) for (i in 0 until each) {
            val likedAt = if (r.nextInt(10) == 0) now - (1 + r.nextInt(120)) * day else null
            w.song("h${a}s$i", "heard$a", "Heard $a $i", likedAt, inLibrary = r.nextInt(3) > 0)
        }
        for (k in 0 until r.nextInt(7)) for (i in 0 until 1 + r.nextInt(3)) w.song("n${k}s$i", "stranger$k", "Stranger $k $i", inLibrary = r.nextInt(5) == 0)
        val all = w.songs.keys.toList()
        val own = all.filter { it.startsWith("h") }
        val span = if (months || r.nextInt(3) == 0) 100 else 12
        var session = 0L
        repeat(if (months) 15 + r.nextInt(26) else 1 + r.nextInt(8)) {
            session++
            val daysAgo = r.nextDouble() * span
            repeat(1 + r.nextInt(5)) { k ->
                val skipped = r.nextInt(6) == 0
                w.play(own[r.nextInt(own.size)], hoursAgo = daysAgo * 24 + 2 - k * 0.07, session = session, ratio = if (skipped) 0.05 else 1.0, ended = if (skipped) EndReason.SKIPPED else EndReason.ENDED)
            }
        }
        if (r.nextInt(4) == 0) { session++; repeat(1 + r.nextInt(3)) { k -> w.play(own[r.nextInt(own.size)], hoursAgo = 0.3 - k * 0.06, session = session) } }
        for (played in w.listens.map { it.songId }.distinct()) if (r.nextBoolean()) repeat(1 + r.nextInt(8)) {
            w.edge(played, all[r.nextInt(all.size)], listOf(Provenance.YOUTUBE, Provenance.YOUTUBE, Provenance.LASTFM, Provenance.SOURCES)[r.nextInt(4)])
        }
        return w
    }

    /**
     * Whether the lean's lane is sure to be empty, told from the library alone and not from the
     * build: nothing never heard, no artist come back to, nothing played or liked long enough ago
     * to be forgotten, no related list for any song of the session the latest listen is in or of
     * the one the latest listen heard through is in.
     */
    private fun sureToHaveNothing(lean: Lean, w: Library): Boolean = when (lean) {
        Lean.NEW -> w.songs.values.none { s -> !s.liked && !s.inLibrary && w.listens.none { it.songId == s.id } }
        Lean.ARTIST -> EngineRow.returningArtists(w.input(), LibraryStats(w.input())).isEmpty()
        Lean.FORGOTTEN -> w.listens.none { it.endedAt < now - 45 * day } && w.songs.values.none { (it.likedAt ?: now) < now - 45 * day }
        Lean.SIMILAR -> {
            val latestFirst = w.listens.sortedByDescending { it.startedAt }
            val last = setOfNotNull(latestFirst.firstOrNull()?.sessionId, latestFirst.firstOrNull { it.playedMs >= it.durationMs }?.sessionId)
            val songs = w.listens.filter { it.sessionId in last }.mapTo(HashSet()) { it.songId }
            w.edges.none { it.seedId in songs }
        }
        Lean.AUTO -> false
    }

    @Test
    fun `over small libraries no lean gives less than Auto when it has nothing, or too short a row when Auto's is long enough`() {
        val sure = HashMap<Lean, Int>(); val gaveWayEmpty = HashMap<Lean, Int>(); val gaveWayShort = HashMap<Lean, Int>(); val followed = HashMap<Lean, Int>()
        var strictGaveWay = 0; var bothShort = 0
        for (seed in 0 until 300) {
            val w = library(seed)
            for (lean in leans) for (s in settings) {
                val shape = EngineRow.shape(newOnly = s.newOnly, chip = s.chip, stored = lean)
                if (shape.lean == Lean.AUTO) continue
                repeat(2) { dice ->
                    val what = "library $seed, $lean, ${s.name} #$dice"
                    val auto = build(w, s, dice)
                    val leaned = build(w, s, dice, lean)
                    // Never a row too short to show where Auto's is long enough.
                    if (auto.cards.size >= minCards) assertTrue("$what: ${leaned.cards.size} cards, Auto has ${auto.cards.size}", leaned.cards.size >= minCards)
                    // Nothing to lean on: Auto's row, the same cards in the same order.
                    if (shape.lead != null && sureToHaveNothing(lean, w)) {
                        assertEquals(what, cards(auto), cards(leaned))
                        sure.merge(lean, 1, Int::plus)
                    }
                    val gaveWay = leaned.gaveWay
                    if (gaveWay != null) {
                        // Auto's row in everything, the recorded lean and weight included.
                        assertEquals(what, sig(auto), sig(leaned))
                        assertEquals(lean, gaveWay.lean); assertEquals(lean, leaned.asked)
                        if (gaveWay.nothing) gaveWayEmpty.merge(lean, 1, Int::plus) else {
                            // Too short a row: only said when Auto's really is long enough.
                            assertTrue(what, auto.cards.size >= minCards)
                            gaveWayShort.merge(lean, 1, Int::plus)
                        }
                        if (shape.lead == null) strictGaveWay++
                    } else {
                        // A row that follows the lean holds some of what it promises, unless Auto's
                        // row would not have been shown either, when the leaned one stands.
                        if (shape.lead != null) { assertEquals(what, lean, leaned.lean); assertTrue(what, leaned.leanPlaced >= 1) }
                        if (leaned.cards.size < minCards) { assertTrue(what, auto.cards.size < minCards); bothShort++ }
                        followed.merge(lean, 1, Int::plus)
                    }
                }
            }
        }
        // The libraries reach every case, so none of the above passes for want of examples.
        for (lean in leans) {
            assertTrue("$lean sure to have nothing in ${sure[lean]} builds", (sure[lean] ?: 0) >= 40)
            assertTrue("$lean gave way with nothing in ${gaveWayEmpty[lean]} builds", (gaveWayEmpty[lean] ?: 0) >= 40)
            assertTrue("$lean followed in ${followed[lean]} builds", (followed[lean] ?: 0) >= 40)
        }
        assertTrue("gave way for a short row: $gaveWayShort", gaveWayShort.values.sum() >= 20)
        assertTrue("Never heard with New songs only gave way in $strictGaveWay builds", strictGaveWay >= 10)
        assertTrue("both rows too short in $bothShort builds", bothShort >= 20)
    }

    @Test
    fun `a row that gave way is recorded and graded as the Auto row it is`() {
        val w = nothingForAnyLean()
        for (lean in leans) {
            val row = build(w, settings.first(), 3, lean)
            val auto = build(w, settings.first(), 3)
            assertNotNull(row.gaveWay)
            // What row_build keeps and learning reads: no lean followed, every card at full weight.
            assertEquals(Lean.AUTO, row.lean); assertEquals(1.0, row.leadWeight, 0.0); assertEquals(0, row.leanPlaced)
            assertEquals(EngineParams.DEFAULT.maxPerArtist, row.leadArtistCap)
            assertEquals(auto.quotas, row.quotas)
            val recorded = BuildLean(1, row.lean.code, row.leadWeight)
            for (lane in Lane.entries) assertEquals(0.3, LeanWeighting.gradedU(0.3, 1, lane, recorded), 0.0)
            // No spares after the pool's forty, and a pull sets the whole row aside, as for any Auto row.
            assertEquals(row.pool, LeanWeighting.poolPickCandidates(row.pool))
            assertEquals(row.cards.mapTo(HashSet()) { it.songId }, LeanRow.pullBanned(row.cards, row.lean, EngineParams.DEFAULT.columns))
            // The heading still names the lean, and says it has nothing yet.
            val onScreen = LeanOnScreen(lean, row.asked, 0, 0, 0, row.cards.size, gaveWay = row.gaveWay)
            assertEquals(LeanHeadingKind.NOTHING, onScreen.heading)
            assertEquals(lean == Lean.FORGOTTEN, onScreen.forgottenHasNothing)
            // Playing now still follows the session while it has nothing to give.
            assertEquals(lean == Lean.SIMILAR, LeanRow.similarRebuildDue(row.asked, 3))
        }
        // An Auto row, and a row that follows its lean, gave way to nothing.
        assertNull(build(w, settings.first(), 3).gaveWay)
    }

    @Test
    fun `Never heard with New songs only gives way to the ordinary New songs only row when its own is too short to show`() {
        // Two artists never heard of, two songs each: four cards under the strict rule. The
        // ordinary rule also takes library songs never played, and has a row to show.
        val w = Library(now)
        for (a in 0 until 3) for (i in 0 until 4) w.song("k${a}s$i", "known$a", "Known $a $i")
        for (a in 0 until 2) for (i in 0 until 2) w.song("n${a}s$i", "fresh$a", "Fresh $a $i", inLibrary = false)
        for (i in 0 until 6) w.song("lib$i", "shelf$i", "Shelf $i")
        val targets = (0 until 2).flatMap { a -> (0 until 2).map { "n${a}s$it" } } + (0 until 6).map { "lib$it" }
        for (a in 0 until 3) for (i in 0 until 4) targets.forEach { w.edge("k${a}s$i", it) }
        for (d in 1..10) for (a in 0 until 3) w.play("k${a}s${d % 4}", hoursAgo = d * 24.0 + a, session = d.toLong())
        val newOnly = settings.first { it.newOnly }
        repeat(5) { dice ->
            val plain = build(w, newOnly, dice)
            val strict = build(w, newOnly, dice, Lean.NEW)
            assertTrue(plain.cards.size >= minCards)
            assertEquals(sig(plain), sig(strict))
            assertEquals(LeanGaveWay(Lean.NEW, nothing = false), strict.gaveWay)
        }
        // With enough never heard for a row, the strict rule stands and gives way to nothing.
        for (a in 2 until 5) for (i in 0 until 2) { w.song("n${a}s$i", "fresh$a", "Fresh $a $i", inLibrary = false); for (k in 0 until 3) w.edge("k${k}s0", "n${a}s$i") }
        repeat(5) { dice ->
            val strict = build(w, newOnly, dice, Lean.NEW)
            assertNull(strict.gaveWay)
            assertTrue(strict.cards.size >= minCards)
            strict.cards.forEach { assertTrue(it.songId, it.songId.startsWith("n")) }
        }
    }
}
