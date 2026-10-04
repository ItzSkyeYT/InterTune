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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Quick picks leans toward: each choice delivers what its label says, and Auto, or any lean a
 * chip sets aside, is exactly the row there was before. On a made-up library, so these run on
 * every build with no data of anyone's.
 */
class LeanTest {
    private val now = 1_789_135_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private class World(val now: Long) {
        val songs = LinkedHashMap<String, SongRow>()
        val listens = ArrayList<ListenRow>()
        val edges = ArrayList<Edge>()
        fun song(id: String, artist: String, title: String = id, liked: Boolean = false, likedAt: Long? = null, inLibrary: Boolean = true) {
            songs[id] = SongRow(id, title, "art_$artist", artist, liked, likedAt?.takeIf { liked }, inLibrary)
        }
        fun play(id: String, hoursAgo: Double, ratio: Double = 1.0, origin: PlayOrigin = PlayOrigin.SEARCH, session: Long, ended: Int = EndReason.ENDED) {
            val start = now - (hoursAgo * 3_600_000).toLong()
            val dur = 200_000L; val played = (dur * ratio).toLong()
            val end = if (ended == EndReason.OPEN) now else start + played
            listens += ListenRow(id, start, end, played, dur, ended, origin.code, 0, session, 0)
        }
        fun edge(seed: String, vararg to: String) = to.forEach { edges += Edge(seed, it) }
        fun input(chip: Int = ContextChip.AUTO) = EngineInput(now, songs, listens, edges, chip = chip)
    }

    /**
     * Twenty artists of eight songs, as in EngineRowTest, with a library the way the song table
     * really is: artists 12 to 19 are only there because related lists named them, so they are
     * not in the library. Artists 0 to 7 were heard daily over the last month, 8 to 11 two months
     * ago, 12 to 19 never. Each of 0 to 7 also has four songs never heard and not in the library,
     * for Never heard's second tier. [richNew] adds twenty-four more new artists, three for each
     * of 0 to 7, so the first tier is large and spread over many artists, as a real related graph
     * is; without it new artists come only through two lightly played songs.
     */
    private fun world(richNew: Boolean = true, nowSession: Boolean = true): World {
        val w = World(now)
        for (a in 0 until 20) for (i in 0 until 8) w.song("a${a}s$i", "artist$a", title = "Song $a $i", inLibrary = a < 12)
        for (a in 0 until 20) w.song("a${a}live", "artist$a", title = "Song $a 0 (Live)", inLibrary = a < 12)
        for (a in 0 until 8) for (i in 0 until 4) w.song("a${a}x$i", "artist$a", title = "Extra $a $i", inLibrary = false)
        for (a in 0 until 20) for (i in 0 until 8) for (n in 1..2) w.edge("a${a}s$i", *(0 until 8).map { "a${(a + n) % 20}s$it" }.toTypedArray())
        for (a in 0 until 8) for (i in 0 until 4) w.edge("a${a}s$i", *(0 until 4).map { "a${(a + 1) % 8}x$it" }.toTypedArray())
        if (richNew) {
            for (k in 0 until 24) for (i in 0 until 2) w.song("n${k}s$i", "newartist$k", title = "Fresh $k $i", inLibrary = false)
            // Each artist's songs refer three new artists of their own, as related lists differ.
            for (a in 0 until 8) for (i in 0 until 4) w.edge("a${a}s$i", *(0 until 3).flatMap { j -> (0 until 2).map { "n${a + 8 * j}s$it" } }.toTypedArray())
        }
        var sess = 1L
        for (d in 1..30) { sess++; for (a in 0 until 8) w.play("a${a}s${d % 4}", hoursAgo = d * 24.0 + a, session = sess) }
        for (d in 50..70) { sess++; for (a in 8..11) w.play("a${a}s${d % 8}", hoursAgo = d * 24.0 + a, session = sess) }
        w.play("a1s1", hoursAgo = 20.0, session = 998); w.play("a2s2", hoursAgo = 19.5, session = 998); w.play("a3s3", hoursAgo = 19.0, session = 998)
        val back = if (nowSession) 0.0 else 2.0
        // The session under way: two songs by two artists, so their related lists differ.
        w.play("a0s1", hoursAgo = back + 0.5, session = 999); w.play("a5s2", hoursAgo = back + 0.2, session = 999)
        return w
    }

    /** Everything a row is, as text, so two builds can be compared exactly. */
    private fun sig(r: BuiltRow): String = buildString {
        r.cards.forEach { c -> append("${c.songId}|${c.lane}|${c.reasons}|${"%.6f".format(c.p)}|${c.seedId}|${c.sampled}|${c.sources};") }
        append("#"); r.pool.forEach { append("${it.songId}|${it.lane}|${it.reasons};") }
        append("#${r.seeds}#${r.quotas}#${r.lastFmShare}#${r.lean}#${r.leanPlaced}#${r.leadArtistCap}#${r.leadWeight}")
    }

    private val chips = listOf(ContextChip.AUTO, ContextChip.DISCOVER, ContextChip.FAVOURITES, ContextChip.FOCUS, ContextChip.CHILL, ContextChip.PARTY)

    @Test
    fun `a lean is stored by code and read back by name, anything unknown is Auto`() {
        Lean.entries.forEach { assertEquals(it, Lean.ofCode(it.code)); assertEquals(it, Lean.ofName(it.name)) }
        assertEquals(Lean.AUTO, Lean.ofName(null)); assertEquals(Lean.AUTO, Lean.ofName("SOMETHING")); assertEquals(Lean.AUTO, Lean.ofCode(9))
        assertEquals(listOf(0, 1, 2, 3, 4), Lean.entries.map { it.code })
        assertEquals(listOf(null, Lane.EXPLORE, Lane.ARTIST, Lane.REDISCOVER, Lane.RELATED), Lean.entries.map { it.lane })
    }

    @Test
    fun `which lean a build follows`() {
        for (stored in Lean.entries) for (chip in chips) for (newOnly in listOf(false, true)) for (neverPlayed in listOf(false, true)) {
            val expected = when {
                neverPlayed || stored == Lean.AUTO -> Lean.AUTO
                newOnly -> if (stored == Lean.NEW) Lean.NEW else Lean.AUTO
                chip == ContextChip.FAVOURITES -> Lean.AUTO
                chip == ContextChip.DISCOVER -> if (stored == Lean.NEW) Lean.NEW else Lean.AUTO
                chip in ContextChip.MOODS -> if (stored == Lean.SIMILAR) Lean.AUTO else stored
                else -> stored
            }
            assertEquals("$stored chip $chip newOnly $newOnly neverPlayed $neverPlayed", expected, Lean.applied(stored, chip, newOnly, neverPlayed))
        }
        // The cases the rule is about, spelled out.
        assertEquals(Lean.NEW, Lean.applied(Lean.NEW, ContextChip.DISCOVER, false, false))
        assertEquals(Lean.AUTO, Lean.applied(Lean.ARTIST, ContextChip.DISCOVER, false, false))
        assertEquals(Lean.FORGOTTEN, Lean.applied(Lean.FORGOTTEN, ContextChip.CHILL, false, false))
        assertEquals(Lean.AUTO, Lean.applied(Lean.SIMILAR, ContextChip.CHILL, false, false))
        assertEquals(Lean.NEW, Lean.applied(Lean.NEW, ContextChip.AUTO, true, false))
        assertEquals(Lean.AUTO, Lean.applied(Lean.NEW, ContextChip.AUTO, false, true))
    }

    @Test
    fun `Auto stored is exactly the row built without a lean`() {
        val w = world()
        for (chip in chips) for (newOnly in listOf(false, true)) for (neverPlayed in listOf(false, true)) {
            val seeds = if (chip == ContextChip.AUTO && !newOnly && !neverPlayed) 20 else 3
            repeat(seeds) { s ->
                val plain = EngineRow.build(w.input(chip), newOnly = newOnly, neverPlayed = neverPlayed, random = Random(s.toLong()), lastFmShare = 0.5)
                val auto = EngineRow.build(w.input(chip), newOnly = newOnly, neverPlayed = neverPlayed, random = Random(s.toLong()), lastFmShare = 0.5, stored = Lean.AUTO)
                assertEquals(sig(plain), sig(auto))
                assertEquals(Lean.AUTO, auto.lean); assertEquals(0, auto.leanPlaced); assertEquals(1.0, auto.leadWeight, 0.0)
            }
        }
    }

    @Test
    fun `a lean set aside builds exactly Auto's row, dice and all`() {
        // A long session with a song playing, so Playing now's own seed draw would differ from
        // Auto's: if a set-aside lean reached the seeds, the row would show it.
        val w = world()
        for ((k, a) in listOf(1, 2, 3, 4, 6, 7).withIndex()) w.play("a${a}s${k % 4}", hoursAgo = 0.45 - k * 0.04, session = 999)
        w.play("a7s6", hoursAgo = 10.0 / 3600, ratio = 0.05, session = 999, ended = EndReason.OPEN)
        var checked = 0
        for (stored in Lean.entries.filter { it != Lean.AUTO }) for (chip in chips) for (newOnly in listOf(false, true)) for (neverPlayed in listOf(false, true)) {
            if (Lean.applied(stored, chip, newOnly, neverPlayed) != Lean.AUTO) continue
            repeat(3) { s ->
                val auto = EngineRow.build(w.input(chip), newOnly = newOnly, neverPlayed = neverPlayed, random = Random(s.toLong()))
                val leaned = EngineRow.build(w.input(chip), newOnly = newOnly, neverPlayed = neverPlayed, random = Random(s.toLong()), stored = stored)
                assertEquals("$stored chip $chip newOnly $newOnly neverPlayed $neverPlayed", sig(auto), sig(leaned))
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    @Test
    fun `quotas give the lead lane twelve and the rest the blend's proportions`() {
        val p = EngineParams.DEFAULT.withFamiliarity(0.25)
        fun q(lean: Lean, cards: Int = p.leanCards) = quotas(20, 0.5, false, p, lean, cards).let { m -> listOf(Lane.EXPLORE, Lane.RELATED, Lane.AGAIN, Lane.ARTIST, Lane.REDISCOVER).map { m[it] } }
        assertEquals(listOf(2, 7, 4, 4, 3), q(Lean.AUTO))
        assertEquals(listOf(12, 3, 2, 2, 1), q(Lean.NEW))
        assertEquals(listOf(1, 4, 2, 12, 1), q(Lean.ARTIST))
        assertEquals(listOf(1, 3, 2, 2, 12), q(Lean.FORGOTTEN))
        assertEquals(listOf(1, 12, 3, 2, 2), q(Lean.SIMILAR))
        assertEquals(listOf(16, 2, 1, 1, 0), q(Lean.NEW, 16))
        for (lean in Lean.entries) for (dial in listOf(0.0, 0.5, 1.0)) for (fam in listOf(0.0, 0.25, 0.45)) {
            val m = quotas(20, dial, false, EngineParams.DEFAULT.withFamiliarity(fam), lean)
            assertEquals(20, m.values.sum())
            lean.lane?.let { assertEquals(12, m[it]) }
        }
        // Familiarity still moves Again inside the eight that are left.
        val low = quotas(20, 0.5, false, EngineParams.DEFAULT.withFamiliarity(0.0), Lean.ARTIST)
        val high = quotas(20, 0.5, false, EngineParams.DEFAULT.withFamiliarity(0.6), Lean.ARTIST)
        assertEquals(0, low[Lane.AGAIN]); assertTrue(high[Lane.AGAIN]!! >= 4)
        // New songs only decides the whole row whatever the lean.
        assertEquals(quotas(20, 0.5, true, p), quotas(20, 0.5, true, p, Lean.ARTIST))
    }

    @Test
    fun `the first column is the lean's, and its cards say why first`() {
        val w = world()
        for (lean in listOf(Lean.NEW, Lean.ARTIST, Lean.SIMILAR)) repeat(5) { s ->
            val row = EngineRow.build(w.input(), random = Random(s.toLong()), stored = lean)
            val lead = lean.lane!!
            assertEquals(lean, row.lean)
            assertEquals(20, row.cards.size)
            assertEquals("$lean seed $s", List(4) { lead }, row.cards.take(4).map { it.lane })
            assertEquals(row.cards.count { it.lane == lead }, row.leanPlaced)
            assertTrue("$lean placed ${row.leanPlaced}", row.leanPlaced >= 12)
            val own = when (lead) { Lane.EXPLORE -> "new_to_you"; Lane.ARTIST -> "x_art"; Lane.RELATED -> "x_seed"; else -> "x_dorm" }
            row.cards.filter { it.lane == lead }.forEach { assertEquals(own, it.reasons.first()) }
            // The rest of the row is the usual mix: every other lane that has songs is in it.
            assertTrue(row.cards.drop(4).any { it.lane != lead })
            // The weight that gives the lead lane its Auto share of the evidence.
            assertEquals(LeanWeighting.leadWeight(EngineRow.shape(stored = lean).autoQuotas[lead]!!, 12, 20), row.leadWeight, 1e-12)
        }
    }

    @Test
    fun `Never heard is songs with no listen at all, not liked and not in the library, new artists first`() {
        val rich = world(richNew = true)
        val heard = rich.listens.mapTo(HashSet()) { it.songId }
        val knownArtists = rich.listens.mapNotNullTo(HashSet()) { rich.songs[it.songId]?.artistId }
        repeat(5) { s ->
            val row = EngineRow.build(rich.input(), random = Random(s.toLong()), stored = Lean.NEW)
            val lead = row.cards.filter { it.lane == Lane.EXPLORE } + row.pool.filter { it.lane == Lane.EXPLORE }
            assertTrue(row.leanPlaced >= 12)
            lead.forEach { c ->
                assertTrue("${c.songId} heard", c.songId !in heard)
                assertFalse("${c.songId} in the library", rich.songs[c.songId]!!.inLibrary || rich.songs[c.songId]!!.liked)
                // Plenty of new artists: the second tier, known artists' unheard songs, never comes in.
                assertTrue("${c.songId} by a known artist", rich.songs[c.songId]!!.artistId !in knownArtists)
            }
        }
        // Few new artists: their songs first, then the second tier fills the lean.
        val poor = world(richNew = false)
        val poorRow = EngineRow.build(poor.input(), random = Random(1), stored = Lean.NEW)
        val poorLead = poorRow.cards.filter { it.lane == Lane.EXPLORE }
        assertTrue(poorLead.any { poor.songs[it.songId]!!.artistId in knownArtists })
        poorLead.forEach { assertTrue(it.songId !in heard); assertFalse(poor.songs[it.songId]!!.inLibrary) }
        // Under Discover: sixteen, new artists only, so never the second tier, even when that leaves it short.
        val discover = EngineRow.build(poor.input(ContextChip.DISCOVER), random = Random(1), stored = Lean.NEW)
        assertEquals(16, discover.quotas[Lane.EXPLORE]); assertEquals(Lean.NEW, discover.lean)
        discover.cards.filter { it.lane == Lane.EXPLORE }.forEach { assertTrue(poor.songs[it.songId]!!.artistId !in knownArtists) }
        val richDiscover = EngineRow.build(rich.input(ContextChip.DISCOVER), random = Random(1), stored = Lean.NEW)
        assertEquals(16, richDiscover.leanPlaced)
        assertEquals(List(4) { Lane.EXPLORE }, richDiscover.cards.take(4).map { it.lane })
        assertEquals(0.25, richDiscover.leadWeight, 1e-12)
        // With New songs only, the whole row is never played and none of it liked or in the library.
        val strict = EngineRow.build(rich.input(), newOnly = true, random = Random(1), stored = Lean.NEW)
        assertEquals(Lean.AUTO, strict.lean)
        assertEquals(20, strict.cards.size)
        strict.cards.forEach { c -> assertTrue(c.songId !in heard); assertFalse(rich.songs[c.songId]!!.inLibrary || rich.songs[c.songId]!!.liked) }
    }

    @Test
    fun `Your artists is artists come back to on different days, never a one-night binge`() {
        val w = world()
        // Artist 13 had one night of it: six songs heard well in one session, yesterday.
        for (i in 0 until 6) w.play("a13s$i", hoursAgo = 30.0 - i * 0.1, session = 997)
        val stats = LibraryStats(w.input())
        val returning = EngineRow.returningArtists(w.input(), stats)
        assertTrue("art_artist13" !in returning)
        assertTrue((0 until 12).all { "art_artist$it" in returning })
        repeat(10) { s ->
            val row = EngineRow.build(w.input(), random = Random(s.toLong()), stored = Lean.ARTIST)
            (row.cards + row.pool).filter { it.lane == Lane.ARTIST }.forEach { assertTrue(it.songId, w.songs[it.songId]!!.artistId in returning) }
            assertEquals(List(4) { Lane.ARTIST }, row.cards.take(4).map { it.lane })
        }
        // Not even a day: three sessions on one day is still a binge.
        val oneDay = world()
        for ((k, sess) in listOf(901L, 902L, 903L).withIndex()) oneDay.play("a14s$k", hoursAgo = 30.0 + k, session = sess)
        assertTrue("art_artist14" !in EngineRow.returningArtists(oneDay.input(), LibraryStats(oneDay.input())))
    }

    @Test
    fun `New songs only under Never heard keeps its stricter rule when related cards fill in`() {
        // Known artists' songs refer five new artists with three songs each, which the cap of two
        // an artist leaves short of twenty but long enough to show, and songs that are new under
        // New songs only but not never heard: a library song never played and a song started and skipped.
        val w = World(now)
        for (a in 0 until 3) for (i in 0 until 4) w.song("k${a}s$i", "known$a", title = "Known $a $i")
        for (a in 0 until 5) for (i in 0 until 3) w.song("n${a}s$i", "fresh$a", title = "Fresh $a $i", inLibrary = false)
        for (i in 0 until 6) w.song("lib$i", "shelf$i", title = "Shelf $i", inLibrary = true)
        for (i in 0 until 6) w.song("skip$i", "skipped$i", title = "Skipped $i", inLibrary = false)
        val targets = (0 until 5).flatMap { a -> (0 until 3).map { "n${a}s$it" } } + (0 until 6).map { "lib$it" } + (0 until 6).map { "skip$it" }
        for (a in 0 until 3) for (i in 0 until 4) w.edge("k${a}s$i", *targets.toTypedArray())
        for (d in 1..10) for (a in 0 until 3) w.play("k${a}s${d % 4}", hoursAgo = d * 24.0 + a, session = d.toLong())
        for (i in 0 until 6) w.play("skip$i", hoursAgo = 30.0 + i, ratio = 0.05, session = 50, ended = EndReason.SKIPPED)
        val heard = w.listens.mapTo(HashSet()) { it.songId }
        repeat(5) { s ->
            val strict = EngineRow.build(w.input(), newOnly = true, random = Random(s.toLong()), stored = Lean.NEW)
            assertEquals(10, strict.cards.size); assertNull(strict.gaveWay)
            strict.cards.forEach { c -> assertTrue("${c.songId} heard", c.songId !in heard); assertFalse("${c.songId} in the library", w.songs[c.songId]!!.inLibrary) }
            // The plain rule lets both kinds in, so the difference is real.
            val plain = EngineRow.build(w.input(), newOnly = true, random = Random(s.toLong()))
            assertTrue(plain.cards.any { it.songId.startsWith("lib") || it.songId.startsWith("skip") })
        }
    }

    /** Three artists come back to, each with ten songs never heard and nowhere in the library or likes; nothing else to lean on. */
    private fun threeArtists(): World {
        val w = World(now)
        for (a in 0 until 3) {
            for (i in 0 until 4) w.song("r${a}h$i", "returning$a", title = "Heard $a $i")
            for (i in 0 until 10) w.song("r${a}n$i", "returning$a", title = "New $a $i", inLibrary = false)
        }
        for (a in 0 until 6) for (i in 0 until 6) w.song("o${a}s$i", "other$a", title = "Other $a $i", inLibrary = false)
        for (a in 0 until 3) for (i in 0 until 4) w.edge("r${a}h$i", *(0 until 6).map { "o${a}s$it" }.toTypedArray(), *(0 until 6).map { "o${a + 3}s$it" }.toTypedArray())
        // Played in four sessions on four days, three weeks ago and more, so nothing is quiet-cut.
        for (d in 0 until 4) for (a in 0 until 3) w.play("r${a}h$d", hoursAgo = (20 + d) * 24.0 + a, session = 10L + d)
        return w
    }

    @Test
    fun `Your artists reads every song of theirs, heard or not, in the library or not`() {
        val w = threeArtists()
        val row = EngineRow.build(w.input(), random = Random(3), stored = Lean.ARTIST)
        // If the source clause ever stopped saying "every song" for this lean, these would be gone.
        assertEquals(12, row.leanPlaced)
        row.cards.filter { it.lane == Lane.ARTIST }.forEach { assertTrue(it.songId.startsWith("r") && "n" in it.songId) }
    }

    @Test
    fun `few returning artists may hold four cards each, one an artist a column`() {
        val w = threeArtists()
        repeat(5) { s ->
            val row = EngineRow.build(w.input(), random = Random(s.toLong()), stored = Lean.ARTIST)
            assertEquals(4, row.leadArtistCap)
            val lead = row.cards.filter { it.lane == Lane.ARTIST }
            assertEquals(12, lead.size)
            lead.groupingBy { w.songs[it.songId]!!.artistId }.eachCount().values.forEach { assertEquals(4, it) }
            row.cards.chunked(4).forEach { col -> assertEquals(col.size, col.map { w.songs[it.songId]!!.artistId }.toSet().size) }
            // And the tidy pass, with its cap of two, leaves them all with the lead cards' own cap.
            val pass = TidyPass(emptyList())
            val kept = pass.row(row.cards, freshOnly = true, id = { it.songId }, title = { w.songs[it.songId]!!.title }, artist = { w.songs[it.songId]!!.artistName },
                maxPerArtist = 2, capOf = { c -> if (c.lane == Lane.ARTIST) row.leadArtistCap else 2 })
            assertEquals(12, kept.count { it.lane == Lane.ARTIST })
            val plain = TidyPass(emptyList()).row(row.cards, freshOnly = true, id = { it.songId }, title = { w.songs[it.songId]!!.title }, artist = { w.songs[it.songId]!!.artistName }, maxPerArtist = 2)
            assertEquals(6, plain.count { it.lane == Lane.ARTIST })
        }
        assertEquals(2, EngineRow.leadArtistCap(0)); assertEquals(2, EngineRow.leadArtistCap(10)); assertEquals(3, EngineRow.leadArtistCap(5))
        assertEquals(4, EngineRow.leadArtistCap(3)); assertEquals(4, EngineRow.leadArtistCap(1))
    }

    @Test
    fun `Forgotten is loved, quiet for the dormancy window and not started for a fortnight`() {
        val w = world()
        // Loved by a like a hundred days ago, heard once since, long ago.
        w.song("a15s0", "artist15", title = "Song 15 0", liked = true, likedAt = now - 100 * day)
        w.play("a15s0", hoursAgo = 90 * 24.0, session = 500)
        // Heard well three times two months ago, then started and skipped five days ago: not forgotten.
        for (k in 0 until 3) w.play("a16s0", hoursAgo = (60 + k) * 24.0, session = 600L + k)
        w.play("a16s0", hoursAgo = 5 * 24.0, ratio = 0.05, session = 610, ended = EndReason.SKIPPED)
        // Heard well once, two months ago: not loved.
        w.play("a17s0", hoursAgo = 60 * 24.0, session = 620)
        val stats = LibraryStats(w.input())
        repeat(5) { s ->
            val row = EngineRow.build(w.input(), random = Random(s.toLong()), stored = Lean.FORGOTTEN)
            val lead = (row.cards + row.pool).filter { it.lane == Lane.REDISCOVER }
            assertTrue(lead.isNotEmpty())
            lead.forEach { c ->
                val st = stats.songs[c.songId]!!
                assertTrue("${c.songId} not loved", w.songs[c.songId]!!.liked || st.goodListens >= 3)
                assertTrue("${c.songId} heard well lately", st.lastGoodAt < now - 45 * day)
                assertTrue("${c.songId} started lately", st.lastStartedAt < now - 14 * day)
            }
            assertTrue(lead.none { it.songId == "a16s0" || it.songId == "a17s0" })
            assertEquals(Lane.REDISCOVER, row.cards.first().lane)
        }
    }

    @Test
    fun `Playing now relates only to the session under way, the song playing included`() {
        val w = world()
        // The song playing now, ten seconds in: below what counts as heard well, but it is what is playing.
        w.play("a6s5", hoursAgo = 10.0 / 3600, ratio = 0.05, session = 999, ended = EndReason.OPEN)
        val session = setOf("a0s1", "a5s2", "a6s5")
        val referredBySession = w.edges.filter { it.seedId in session }.mapTo(HashSet()) { it.songId }
        repeat(5) { s ->
            val row = EngineRow.build(w.input(), random = Random(s.toLong()), stored = Lean.SIMILAR)
            assertTrue("a6s5" in row.seeds)
            val related = (row.cards + row.pool).filter { it.lane == Lane.RELATED }
            assertTrue(related.size >= 12)
            related.forEach { c -> assertTrue("${c.songId} by ${c.seedId}", c.seedId in session && c.songId in referredBySession) }
            assertEquals(List(4) { Lane.RELATED }, row.cards.take(4).map { it.lane })
            row.cards.filter { it.lane == Lane.RELATED }.forEach { assertEquals("x_seed", it.reasons.first()) }
        }
        // Without the lean the playing song is no seed, since it has not been heard well.
        assertTrue((0 until 5).none { "a6s5" in EngineRow.build(w.input(), random = Random(it.toLong())).seeds })
        // A skipped song is not what is playing.
        val skipped = world()
        skipped.play("a6s5", hoursAgo = 1.0 / 60, ratio = 0.05, session = 999, ended = EndReason.SKIPPED)
        assertTrue((0 until 5).none { "a6s5" in EngineRow.build(skipped.input(), random = Random(it.toLong()), stored = Lean.SIMILAR).seeds })
    }

    @Test
    fun `Playing now with nothing playing follows the last session`() {
        val w = world(nowSession = false)
        val last = setOf("a0s1", "a5s2")
        repeat(5) { s ->
            val row = EngineRow.build(w.input(), random = Random(s.toLong()), stored = Lean.SIMILAR)
            assertTrue(last.all { it in row.seeds })
            (row.cards + row.pool).filter { it.lane == Lane.RELATED }.forEach { assertTrue(it.seedId in last) }
        }
    }

    @Test
    fun `a short lead lane hands its places on in proportion, and the row stays full`() {
        val p = EngineParams.DEFAULT.withFamiliarity(0.25)
        val q = quotas(20, 0.5, false, p, Lean.FORGOTTEN)
        fun lane(l: Lane, n: Int) = List(n) { i -> Candidate("${l.name}$i", l, DoubleArray(Features.COUNT), -i.toDouble(), null, "${l.name}$i", "${l.name}$i") }
        val lanes = Lane.entries.associateWith { if (it == Lane.REDISCOVER) lane(it, 3) else lane(it, 40) }
        val placed = Assembly(lanes, q, Weights.PRIORS, p, Random(1), null, Lane.REDISCOVER).run()
        assertEquals(20, placed.size)
        val counts = placed.groupingBy { it.first.lane }.eachCount()
        assertEquals(3, counts[Lane.REDISCOVER])
        // Quotas related 3, again 2, artist 2, explore 1 take the nine places at about twice their size,
        // not all nine to related as the Auto rule would.
        assertEquals(mapOf(Lane.RELATED to 7, Lane.AGAIN to 4, Lane.ARTIST to 4, Lane.EXPLORE to 2), counts.filterKeys { it != Lane.REDISCOVER })
        // The first column is what the lead lane had; the usual mix fills the rest of it.
        assertEquals(List(3) { Lane.REDISCOVER }, placed.take(3).map { it.first.lane })

        // Through a build: Forgotten with exactly three songs to give.
        val w = World(now)
        for (a in 0 until 12) for (i in 0 until 8) w.song("b${a}s$i", "band$a", title = "Tune $a $i")
        for (a in 0 until 12) for (i in 0 until 8) w.edge("b${a}s$i", *(0 until 8).map { "b${(a + 1) % 12}s$it" }.toTypedArray())
        for (d in 1..20) for (a in 0 until 6) w.play("b${a}s${d % 4}", hoursAgo = d * 24.0 + a, session = d.toLong())
        for (k in 0 until 3) for (a in 6 until 9) w.play("b${a}s0", hoursAgo = (60 + k) * 24.0 + a, session = 100L + k)
        // The three as seeds would keep them out of the row, so they are not seeds here.
        val row = EngineRow.build(w.input().copy(notSeeds = setOf("b6s0", "b7s0", "b8s0")), p = p, random = Random(2), stored = Lean.FORGOTTEN)
        assertEquals(20, row.cards.size)
        assertEquals(3, row.leanPlaced)
        assertEquals(LeanHeadingKind.SHORT, LeanRow.heading(row.lean, row.quotas[Lane.REDISCOVER]!!, row.leanPlaced, 3))
    }

    @Test
    fun `under a lean the pool keeps its forty and adds spares of the lead lane after them`() {
        val w = world()
        val auto = EngineRow.build(w.input(), random = Random(1))
        assertTrue(auto.pool.size <= 40)
        val row = EngineRow.build(w.input(), random = Random(1), stored = Lean.NEW)
        assertTrue(row.pool.size > 40)
        assertTrue(row.pool.drop(40).all { it.lane == Lane.EXPLORE })
        assertTrue(row.pool.drop(40).size <= EngineParams.DEFAULT.leanSpares)
        val groups = VersionGroups(w.songs.values)
        val all = (row.cards + row.pool).map { groups.groupOf(it.songId) }
        assertEquals(all.size, all.toSet().size)
        // Lead cards in the pool say why first too, so a card the tidy pass brings in reads the same.
        row.pool.filter { it.lane == Lane.EXPLORE }.forEach { assertEquals("new_to_you", it.reasons.first()) }
        // The spares are never pool picks: those come from the forty, as without a lean.
        assertEquals(row.pool.take(40), LeanWeighting.poolPickCandidates(row.pool))
        assertTrue(LeanWeighting.poolPickCandidates(row.pool).none { it in row.pool.drop(40) })
        assertEquals(auto.pool, LeanWeighting.poolPickCandidates(auto.pool))
    }

    @Test
    fun `the lead lane's evidence weighs what Auto would have given it`() {
        for (lean in Lean.entries.filter { it != Lean.AUTO }) for (fam in listOf(0.0, 0.25, 0.45)) for (adv in listOf(0.0, 0.5, 1.0)) {
            val p = EngineParams.DEFAULT.withFamiliarity(fam)
            val shape = EngineRow.shape(p, adv, stored = lean)
            val lead = shape.lead!!
            val w = shape.leadWeight
            // Every card graded alike: each lane's share of the summed u.
            val total = Lane.entries.sumOf { (shape.quotas[it] ?: 0) * LeanWeighting.of(it, lead, w) }
            for (l in Lane.entries) {
                val share = (shape.quotas[l] ?: 0) * LeanWeighting.of(l, lead, w) / total
                val autoShare = (shape.autoQuotas[l] ?: 0) / 20.0
                if (l == lead) assertEquals("$lean fam $fam adv $adv", autoShare, share, 1e-9)
                else assertEquals("$lean $l fam $fam adv $adv", autoShare, share, 0.08)
            }
        }
        assertEquals(0.074, LeanWeighting.leadWeight(2, 12, 20), 0.001)
        assertEquals(0.167, LeanWeighting.leadWeight(4, 12, 20), 0.001)
        assertEquals(0.118, LeanWeighting.leadWeight(3, 12, 20), 0.001)
        assertEquals(0.359, LeanWeighting.leadWeight(7, 12, 20), 0.001)
        assertEquals(0.25, LeanWeighting.leadWeight(10, 16, 20), 1e-12)
        assertEquals(1.0, LeanWeighting.leadWeight(3, 0, 20), 0.0)
        assertEquals(1.0, LeanWeighting.leadWeight(3, 3, 20), 0.0)
    }

    @Test
    fun `the pool-pick reference under Never heard has Auto's share of new songs`() {
        val shape = EngineRow.shape(EngineParams.DEFAULT.withFamiliarity(0.25), 0.5, stored = Lean.NEW)
        // Explore cards carry x_novel 1, everything else 0: the reference's x_novel is explore's share.
        val cards = Lane.entries.flatMap { l ->
            List(shape.quotas[l] ?: 0) { i -> Card("$l$i", l, 0.0, 0.1, DoubleArray(Features.COUNT) { f -> if (f == Features.NOVEL && l == Lane.EXPLORE) 1.0 else 0.0 }, emptyList()) }
        }
        val weighed = LeanWeighting.reference(cards, shape.lead, shape.leadWeight)!!
        assertEquals((shape.autoQuotas[Lane.EXPLORE] ?: 0) / 20.0, weighed[Features.NOVEL], 1e-9)
        val plain = LeanWeighting.reference(cards, null, 1.0)!!
        assertEquals(12 / 20.0, plain[Features.NOVEL], 1e-9)
        assertNull(LeanWeighting.reference(emptyList(), shape.lead, shape.leadWeight))
    }

    @Test
    fun `grading scales only the engine's lead cards of a leaned build`() {
        val leaned = BuildLean(7, Lean.NEW.code, 0.074)
        val auto = BuildLean(8, Lean.AUTO.code, 1.0)
        assertEquals(0.3 * 0.074, LeanWeighting.gradedU(0.3, 1, Lane.EXPLORE, leaned), 1e-12)
        assertEquals(1.0 * 0.074, LeanWeighting.gradedU(1.0, 1, Lane.EXPLORE, leaned), 1e-12)
        assertEquals(0.3, LeanWeighting.gradedU(0.3, 1, Lane.RELATED, leaned), 0.0)
        // The other source's card in Try both, the Discover row's, a card with no lane, and no lean.
        assertEquals(0.3, LeanWeighting.gradedU(0.3, 2, Lane.EXPLORE, leaned), 0.0)
        assertEquals(0.3, LeanWeighting.gradedU(0.3, 4, Lane.EXPLORE, leaned), 0.0)
        assertEquals(0.3, LeanWeighting.gradedU(0.3, 1, null, leaned), 0.0)
        assertEquals(0.3, LeanWeighting.gradedU(0.3, 1, Lane.EXPLORE, auto), 0.0)
        assertEquals(0.3, LeanWeighting.gradedU(0.3, 1, Lane.EXPLORE, null), 0.0)
        // What is stored is what Apply and Rebuild replay: the same examples give the same weights.
        val x = DoubleArray(Features.COUNT) { if (it == Features.NOVEL) 1.0 else 0.2 }
        val examples = List(30) { Example(x, Lane.EXPLORE, it % 20, 0.0, LeanWeighting.gradedU(0.3, 1, Lane.EXPLORE, leaned)) }
        val once = Learner().apply { examples.forEach { apply(it) } }.asMap()
        val again = Learner().apply { examples.forEach { apply(it) } }.asMap()
        assertEquals(once, again)
        // And a weighted lean moves x_novel less than the same cards at full weight.
        val full = Learner().apply { examples.forEach { apply(Example(it.features, it.lane, it.slot, it.y, 0.3)) } }.asMap()
        val prior = Features.priors["x_novel"]!!.value
        assertTrue(prior - once["x_novel"]!! < prior - full["x_novel"]!!)
    }
}
