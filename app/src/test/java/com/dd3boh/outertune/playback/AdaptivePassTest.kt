/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.engine.EngineInput
import com.dd3boh.outertune.engine.EngineLoader
import com.dd3boh.outertune.engine.ListenRow
import com.dd3boh.outertune.engine.SongRow
import com.dd3boh.outertune.engine.TagFit
import com.dd3boh.outertune.engine.Weights
import com.dd3boh.outertune.engine.versionKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.AdaptiveQueue.Leave
import com.dd3boh.outertune.utils.SongVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One pass over a radio from what was heard to what changes, driven through AdaptiveQueue.Heard and
 * AdaptivePass with a plain list standing in for the queue: the scenarios the listener was promised.
 */
class AdaptivePassTest {

    private val now = 1_000L * 86_400_000L
    private val empty = QueueFit.Taste.empty(now)

    private fun song(id: String, title: String = "Song $id", artist: String = "Artist $id") =
        MediaMetadata(id = id, title = title, artists = listOf(MediaMetadata.Artist(null, artist)), duration = 200, genre = null)

    /** A radio run that has heard [songs] in turn, each left as said, the last one playing. */
    private fun heard(vararg songs: Pair<MediaMetadata, Leave?>): AdaptiveQueue.Heard {
        val h = AdaptiveQueue.Heard(clock = { now })
        for ((s, how) in songs) {
            h.note(1L, s.id, s.title, s.artists.map { it.name }, s.duration, runId = 5L, radio = true)
            how?.let { h.left(it) }
        }
        return h
    }

    private fun input(
        h: AdaptiveQueue.Heard,
        queue: List<MediaMetadata>,
        kind: AdaptivePass.Kind,
        sources: List<AdaptivePass.Source>,
        taste: QueueFit.Taste = empty,
        skippedRelated: Set<String> = emptySet(),
        offline: Boolean = false,
        playsOffline: (MediaMetadata) -> Boolean = { true },
    ): AdaptivePass.Input {
        val view = h.view()
        val current = queue.indexOfFirst { it.id == view.playingId }
        val locked = if (kind == AdaptivePass.Kind.ORDINARY) AdaptiveQueue.LOCKED else AdaptiveQueue.STRONG_LOCKED
        val settled = AdaptiveQueue.settledThrough(current, queue, { it.id }, view.reached)
        val start = AdaptiveQueue.tailStart(settled, queue.size, locked)!!
        return AdaptivePass.Input(
            kind = kind,
            tail = (start until queue.size).map { AdaptivePass.Place(it, queue[it], queue[it].id in view.byHand) },
            head = queue.subList(current, start),
            queue = queue,
            view = view,
            taste = taste,
            sources = sources,
            skippedRelated = skippedRelated,
            offline = offline,
            playsOffline = playsOffline,
        )
    }

    /** The queue as the plan leaves it, applied the way QueueBoard.applyAdaptive applies it. */
    private fun applied(queue: List<MediaMetadata>, r: AdaptivePass.Result): List<MediaMetadata> {
        val out = queue.toMutableList()
        val plan = r.plan
        for (c in plan.changes.sortedByDescending { it.slot.index }) when (c) {
            is QueuePlanner.Replace -> out[c.slot.index] = r.songs.getValue(c.pick.id)
            is QueuePlanner.Drop -> out.removeAt(c.slot.index)
            is QueuePlanner.Keep -> {}
        }
        if (plan.inserts.isNotEmpty()) out.addAll(plan.insertAt, plan.inserts.map { r.songs.getValue(it.id) })
        return out
    }

    private fun byA(list: List<MediaMetadata>) = list.filter { it.artists.first().name == "Band A" }.map { it.id }

    // ---- 1.1: two songs by one artist skipped.

    @Test
    fun `a skip chooses again a song by the skipped artist close ahead, and a second skip takes all of theirs out`() {
        val q = mutableListOf<MediaMetadata>()
        for (i in 0 until 30) q += when (i) {
            3, 6, 8, 12, 20 -> song("A$i", artist = "Band A")
            else -> song("X$i")
        }
        // Picks related to what was kept: C1 is on the lists of the two newest seeds, the rest on
        // the newest one's only.
        val c = (1..9).map { song("C$it", artist = "Pick artist $it") }
        fun sources(vararg seeds: String) = seeds.mapIndexed { i, seed ->
            AdaptivePass.Source(seed, when (i) { 0 -> c; 1 -> c.take(1); else -> emptyList() })
        }

        // X0, X1, X2 heard to the end, A3 skipped at 0:20: a skip pass, two songs locked.
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to Leave.RAN_OUT, q[2] to Leave.RAN_OUT, q[3] to Leave.SKIPPED, q[4] to null)
        val first = AdaptivePass.run(input(h, q, AdaptivePass.Kind.SKIP, sources("X2", "X1", "X0")))
        assertEquals("the song by A among the next five, nothing else", listOf(8), first.plan.replaced.map { it.slot.index })
        assertEquals("skipped artist", first.plan.replaced.single().slot.byScore)
        assertEquals("C1", first.plan.replaced.single().pick.id)
        val afterFirst = applied(q, first)
        assertEquals("one skip is a mood: A's songs further on stay", listOf("A3", "A6", "A12", "A20"), byA(afterFirst))
        h.picked(mapOf("C1" to "X2"))
        val note = AdaptiveNote.of(first.kind, first.plan.replaced, first.plan.inserts, 0, null, null, h.played)!!
        assertEquals(AdaptiveNote.Kind.AFTER_SKIP, note.kind)
        assertEquals(1, note.count)

        // X4 and X5 heard to the end, then A6 skipped too: A is turned down.
        h.left(Leave.RAN_OUT)
        h.note(1L, "X5", "Song X5", listOf("Artist X5"), 200, runId = 5L, radio = true); h.left(Leave.RAN_OUT)
        h.note(1L, "A6", "Song A6", listOf("Band A"), 200, runId = 5L, radio = true); h.left(Leave.SKIPPED)
        h.note(1L, "X7", "Song X7", listOf("Artist X7"), 200, runId = 5L, radio = true)
        val second = AdaptivePass.run(input(h, afterFirst, AdaptivePass.Kind.SKIP, sources("X5", "X4", "X2", "X1", "X0")))
        val afterSecond = applied(afterFirst, second)
        assertEquals("nothing by A ahead", emptyList<String>(), byA(afterSecond.drop(afterSecond.indexOfFirst { it.id == "X7" } + 1)))
        assertTrue(second.plan.replaced.all { it.slot.unwanted == QueuePlanner.Unwanted.ARTIST_TURNED_DOWN })
        assertTrue("no pick is by A", second.plan.replaced.none { it.pick.artist == "band a" })
        assertEquals(afterFirst.size, afterSecond.size)
    }

    @Test
    fun `on a new install one skip swaps a song by the skipped artist close ahead for a pick like what was kept`() {
        // Two songs kept, then one by Band A skipped. A pick listed by one seed alone fits by less
        // than the margin over the radio's song by A, the skip counted, and changed nothing.
        val q = (0 until 20).map { i -> if (i == 2 || i == 10) song("A$i", artist = "Band A") else song("X$i") }
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to Leave.RAN_OUT, q[2] to Leave.SKIPPED, q[3] to null)
        val related = listOf(AdaptivePass.Source("X1", listOf(song("R1", artist = "Related 1"))))
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.SKIP, related))
        assertEquals(listOf(10 to "R1"), r.plan.replaced.map { it.slot.index to it.pick.id })
        val change = r.plan.replaced.single()
        assertTrue("short of the margin", change.pick.fit < change.slot.fit + QueueFit.REPLACE_MARGIN)
        assertEquals(AdaptiveNote.Kind.AFTER_SKIP, AdaptiveNote.of(r.kind, r.plan.replaced, r.plan.inserts, 0, null, null, 4)!!.kind)

        // After a song simply ended, the same song by A needs the margin: one skip is a mood.
        val later = heard(q[0] to Leave.RAN_OUT, q[1] to Leave.RAN_OUT, q[2] to Leave.SKIPPED, q[3] to Leave.RAN_OUT, q[4] to null)
        val ordinary = AdaptivePass.run(input(later, q, AdaptivePass.Kind.ORDINARY, related))
        assertEquals("skipped artist", ordinary.slots.first { it.id == "A10" }.byScore)
        assertTrue(ordinary.plan.replaced.isEmpty())
    }

    @Test
    fun `a radio whose first song was skipped takes nothing like it, and only loses what is unwanted`() {
        val q = (0 until 20).map { i -> if (i in setOf(1, 9, 13)) song("A$i", artist = "Band A") else song("X$i") }
        val qq = listOf(song("A0", artist = "Band A")) + q.drop(1)
        val h = heard(qq[0] to Leave.SKIPPED, qq[1] to Leave.SKIPPED, qq[2] to null)
        assertTrue("no seed: nothing was kept, and the song it began with was turned away", h.view().seeds.isEmpty())
        val r = AdaptivePass.run(input(h, qq, AdaptivePass.Kind.SKIP, emptyList()))
        assertTrue(r.plan.replaced.isEmpty())
        assertEquals(listOf(9, 13), r.plan.dropped.map { it.slot.index })
    }

    @Test
    fun `the radio's next page leaves out what the run turned down, bans and lasting dislikes, but never all of it`() {
        val taste = tasteSkipping("Band C", 8)
        val withBan = QueueFit.Taste(taste.now, taste.stats, taste.habits, taste.weights, taste.songs, bannedArtists = setOf("banned band"))
        val turned = AdaptiveQueue.TurnedDown(artists = setOf("band a"), songs = setOf(SongVersions.baseTitle("Paper Moon", "Band P")))
        val page = listOf(
            song("a1", artist = "Band A"), song("pm", "Paper Moon (Slowed)", "Band P"), song("b1", artist = "Banned Band"),
            song("c1", artist = "Band C"), song("pv", "Tune (Preview)", "Teasers"), song("ok", artist = "Fine"),
        )
        assertEquals(listOf("ok"), AdaptivePass.pageKeeps(page, turned, emptySet(), withBan).map { it.id })
        assertEquals("an artist kept in this run is no lasting dislike here", listOf("c1", "ok"), AdaptivePass.pageKeeps(page, turned, setOf("band c"), withBan).map { it.id })
        assertEquals("before the taste is read, what the run turned down still goes", listOf("b1", "c1", "ok"), AdaptivePass.pageKeeps(page, turned, emptySet(), null).map { it.id })
        assertEquals("a page that would lose every song goes in whole", listOf("a1", "pm"), AdaptivePass.pageKeeps(page.take(2), turned, emptySet(), withBan).map { it.id })
    }

    @Test
    fun `with one seed an artist turned down far ahead gets picks rather than gaps`() {
        // An artist mix: every fourth song by Band A. Two skips of A after one song kept.
        val q = (0 until 40).map { i -> if (i % 4 == 1) song("A$i", artist = "Band A") else song("X$i") }
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to Leave.SKIPPED, q[2] to Leave.RAN_OUT, q[3] to Leave.RAN_OUT, q[4] to Leave.RAN_OUT, q[5] to Leave.SKIPPED, q[6] to null)
        val related = (1..12).map { song("R$it", artist = "Related $it") }
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.SKIP, listOf(AdaptivePass.Source("X4", related))))
        val ahead = (9 until 40).filter { it % 4 == 1 }
        assertEquals(ahead, (r.plan.replaced.map { it.slot.index } + r.plan.dropped.map { it.slot.index }).sorted())
        assertTrue("more than three picks of the one seed", r.plan.replaced.size > 3)
        val near = r.plan.replaced.count { it.slot.index < 9 + TagFit.WINDOW }
        assertTrue("at most three of them close ahead", near <= 3)
    }

    // ---- 1.2: a song liked.

    @Test
    fun `a like puts two of the liked song's related songs in after the next two, once`() {
        val q = (0 until 20).map { song("X$it") }.toMutableList()
        q[2] = song("L", "Paper Moon (Official Video)", "The Band")
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to Leave.RAN_OUT, q[2] to null)
        h.liked("L")
        val related = (1..4).map { song("P$it", artist = "Related $it") }
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.LIKE, listOf(AdaptivePass.Source("L", related))))

        assertEquals(listOf("P1", "P2"), r.plan.inserts.map { it.id })
        assertEquals("the third song from now", 5, r.plan.insertAt)
        val after = applied(q, r)
        assertEquals(listOf("X3", "X4", "P1", "P2", "X5"), after.subList(3, 8).map { it.id })

        h.likeAnswered("L")
        val again = AdaptivePass.run(input(h, after, AdaptivePass.Kind.ORDINARY, listOf(AdaptivePass.Source("L", related))))
        assertTrue("liking it once puts its picks in once", again.plan.inserts.isEmpty())
    }

    @Test
    fun `a like whose related list is not stored yet waits, claiming nothing`() {
        val q = (0 until 20).map { song("X$it") }
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to null)
        h.liked("X1")
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.LIKE, emptyList()))
        assertFalse(r.plan.changed)
        assertTrue("still waiting for a later pass", h.view().likePending == "X1")
        assertTrue(r.asking >= QueuePlanner.LIKE_PICKS)
    }

    // ---- 1.3: the mood turns.

    @Test
    fun `plain recordings kept and slowed edits skipped make a plain run, and plain picks take the edits' places`() {
        val sequence = listOf(
            song("P1") to Leave.RAN_OUT, song("S1", "S1 (Slowed)") to Leave.SKIPPED,
            song("P2") to Leave.RAN_OUT, song("S2", "S2 (Slowed + Reverb)") to Leave.SKIPPED,
            song("P3") to Leave.KEPT, song("S3", "S3 (Slowed)") to Leave.SKIPPED,
            song("P4") to Leave.RAN_OUT, song("S4", "S4 (Slowed)") to null,
        )
        val ahead = (5..20).map { i -> if (i % 3 == 0) song("T$i", "T$i (Slowed)", "Edits $i") else song("Y$i", artist = "Plain $i") }
        val q = sequence.map { it.first } + ahead
        val h = heard(*sequence.toTypedArray())
        assertTrue("kept: four plain songs", AdaptiveQueue.plainRun(h.keptTitles(AdaptiveQueue.PLAIN_RUN)))
        assertFalse("counted with the skips and the song just started, there was no plain run", AdaptiveQueue.plainRun(h.titles(AdaptiveQueue.PLAIN_RUN)))

        val plain = (1..8).map { song("C$it", artist = "Pick $it") }
        val slowed = (1..8).map { song("D$it", "D$it (Slowed)", "Slowed pick $it") }
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.SKIP, listOf(AdaptivePass.Source("P4", slowed + plain), AdaptivePass.Source("P3", plain))))

        assertTrue(r.plan.replaced.isNotEmpty())
        assertTrue("treated versions make way", r.plan.replaced.all { it.slot.id.startsWith("T") })
        assertTrue("for plain picks only", r.plan.replaced.all { it.pick.id.startsWith("C") })
        assertTrue("no slowed pick is usable in a plain run", r.songs.keys.none { it.startsWith("D") })
    }

    // ---- 1.5: a new install.

    @Test
    fun `with no history and no lists, nothing is claimed and only what was turned down goes`() {
        val q = (0 until 20).map { if (it == 12) song("A12", artist = "Band A") else song("X$it") }
        val h = heard(song("A0", artist = "Band A") to Leave.SKIPPED, song("A1", artist = "Band A") to Leave.SKIPPED, q[2] to null)
        val qq = listOf(song("A0", artist = "Band A"), song("A1", artist = "Band A")) + q.drop(2)
        val r = AdaptivePass.run(input(h, qq, AdaptivePass.Kind.SKIP, emptyList()))
        assertTrue(r.plan.replaced.isEmpty() && r.plan.inserts.isEmpty())
        assertEquals(listOf(12), r.plan.dropped.map { it.slot.index })
        assertEquals(AdaptiveNote.Kind.DROPPED, AdaptiveNote.of(r.kind, r.plan.replaced, r.plan.inserts, r.plan.dropped.size, null, null, 3)!!.kind)
    }

    // ---- 1.4: days of skipping an artist.

    private fun tasteSkipping(artist: String, times: Int): QueueFit.Taste {
        val songs = HashMap<String, SongRow>()
        val listens = ArrayList<ListenRow>()
        var t = now - 2 * 86_400_000L
        for (i in 0 until 40) {
            songs["k$i"] = SongRow("k$i", "Kept $i", "kid$i", "Kept artist $i")
            listens += ListenRow("k$i", t, t + 200_000, 200_000, 200_000, EndReason.ENDED, 0, 0, t, 0); t += 600_000
        }
        for (i in 0 until times) {
            songs["c$i"] = SongRow("c$i", "Skipped $i", "cid", artist)
            listens += ListenRow("c$i", t, t + 40_000, 40_000, 200_000, EndReason.SKIPPED, 0, 0, t, 0); t += 600_000
        }
        return QueueFit.Taste.of(EngineLoader.HeardRows(EngineInput(now, songs, listens, emptyList()), emptyList(), Weights.PRIORS))
    }

    @Test
    fun `an artist skipped for days goes from a new radio once its first song has played, unless the radio is theirs`() {
        val taste = tasteSkipping("Band C", 8)
        val q = (0 until 20).map { if (it in setOf(7, 11, 15)) song("C$it", artist = "Band C") else song("X$it") }
        val related = (1..9).map { song("R$it", artist = "Related $it") }
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to null)
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.ORDINARY, listOf(AdaptivePass.Source("X0", related)), taste = taste))
        assertEquals(listOf(7, 11, 15), r.plan.replaced.map { it.slot.index } + r.plan.dropped.map { it.slot.index })
        assertTrue(r.plan.replaced.all { it.slot.unwanted == QueuePlanner.Unwanted.LASTING_DISLIKE })
        assertEquals(AdaptiveNote.Kind.HABITS, AdaptiveNote.of(r.kind, r.plan.replaced, r.plan.inserts, r.plan.dropped.size, null, null, 2)!!.kind)

        // A radio started from a song by C: what the listener is doing now outranks the habit.
        val theirs = listOf(song("C0", artist = "Band C")) + q.drop(1)
        val started = heard(theirs[0] to Leave.RAN_OUT, theirs[1] to null)
        val none = AdaptivePass.run(input(started, theirs, AdaptivePass.Kind.ORDINARY, listOf(AdaptivePass.Source("C0", related)), taste = taste))
        assertFalse(none.plan.changed)
    }

    // ---- 1.6: offline.

    @Test
    fun `offline only a song that plays without the network is picked`() {
        val q = (0 until 20).map { if (it == 9) song("B9", artist = "Banned Band") else song("X$it") }
        val taste = QueueFit.Taste(now, empty.stats, empty.habits, Weights.PRIORS, emptyMap(), bannedArtists = setOf("banned band"))
        val related = listOf(song("R1"), song("R2"))
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to null)
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.ORDINARY, listOf(AdaptivePass.Source("X0", related)), taste = taste, offline = true, playsOffline = { it.id == "R2" }))
        assertEquals(listOf("R2"), r.plan.replaced.map { it.pick.id })
        val none = AdaptivePass.run(input(h, q, AdaptivePass.Kind.ORDINARY, listOf(AdaptivePass.Source("X0", related)), taste = taste, offline = true, playsOffline = { false }))
        assertEquals("a banned song with no pick is taken out", listOf(9), none.plan.dropped.map { it.slot.index })
    }

    @Test
    fun `an artist skipped now and then can still be picked, after the others, and is not chosen again for it`() {
        val taste = tasteSkipping("Band M", 1)
        val q = (0 until 20).map { if (it == 8) song("B8", artist = "Banned Band") else song("X$it") }
        val withBan = QueueFit.Taste(taste.now, taste.stats, taste.habits, taste.weights, taste.songs, bannedArtists = setOf("banned band"))
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to null)
        val related = listOf(song("M1", artist = "Band M"), song("F1", artist = "Fresh"))
        val r = AdaptivePass.run(input(h, q, AdaptivePass.Kind.ORDINARY, listOf(AdaptivePass.Source("X0", related)), taste = withBan))
        assertEquals("both usable", setOf("M1", "F1"), r.songs.keys)
        assertEquals("the one with no habit first", "F1", r.plan.replaced.single().pick.id)

        // Placed as a pick, its habit is not a reason to choose its place again.
        h.picked(mapOf("M1" to "X0"))
        val placed = q.toMutableList().also { it[7] = related[0] }
        val again = AdaptivePass.run(input(h, placed, AdaptivePass.Kind.ORDINARY, emptyList(), taste = taste))
        assertEquals(null, again.slots.first { it.id == "M1" }.byScore)
    }

    // ---- What a pick may be.

    @Test
    fun `a pick is a song the next pass would keep`() {
        val q = (0 until 20).map { song("X$it") }
        val kept = listOf(song("K1") to Leave.RAN_OUT, song("K2") to Leave.RAN_OUT, song("K3") to Leave.RAN_OUT, song("K4") to Leave.RAN_OUT)
        val h = heard(*(kept + (song("S", "Turned (Slowed)", "Band T") to Leave.SKIPPED) + (song("S2", "Other", "Band T") to Leave.SKIPPED) +
            (song("O", artist = "Once") to Leave.SKIPPED) + (q[0] to null)).toTypedArray())
        h.picked(mapOf("gone-pick" to "K4"))
        h.dismiss(listOf("gone-pick"))
        val queue = kept.map { it.first } + listOf(song("S", "Turned (Slowed)", "Band T"), song("S2", "Other", "Band T"), song("O", artist = "Once")) + q
        val taste = QueueFit.Taste(
            now, empty.stats, empty.habits, Weights.PRIORS, emptyMap(),
            bannedSongIds = setOf("banned"), bannedSongKeys = setOf(versionKey("Banned Upload", "Uploader")),
            bannedArtists = setOf("banned artist"), goneIds = setOf("gone"), freshIds = setOf("just-heard"),
        )
        val candidates = listOf(
            song("X15"),                                        // in the queue
            song("K1-live", "Song K1 (Live)", "Artist K1"),     // a version of a song reached
            song("gone-pick"),                                  // taken out by an earlier pass
            song("by-t", artist = "Band T"),                    // an artist turned down
            song("turned", "Turned (Sped Up)", "Somebody"),     // a version of a song skipped
            song("treated", "Edit (Nightcore)", "Edits"),       // out of place by its words in a plain run
            song("preview", "Tune (Preview)", "Teasers"),       // a preview
            song("banned"),                                     // banned by id
            song("banned-2", "Banned Upload (Remix)", "Uploader"), // another upload of a banned song
            song("by-banned", artist = "Banned Artist"),         // a banned artist
            song("gone"),                                       // no longer served
            song("just-heard"),                                 // heard this session elsewhere
            song("once", artist = "Once"),                      // an artist skipped once in this run
            song("skip-related"),                               // on the skipped song's own list
            song("good", artist = "Fine"),
        )
        val r = AdaptivePass.run(input(h, queue, AdaptivePass.Kind.SKIP, listOf(AdaptivePass.Source("K4", candidates)), taste = taste, skippedRelated = setOf("skip-related")))
        assertEquals(setOf("good"), r.songs.keys)
        assertEquals(candidates.size, r.candidates)
        assertEquals(1, r.usable)
    }

    @Test
    fun `nothing a pass puts in has anything of its own against it in the next pass`() {
        val q = (0 until 25).map { if (it % 4 == 3) song("T$it", "T$it (Nightcore)", "Edits $it") else song("X$it") }
        val h = heard(q[0] to Leave.RAN_OUT, q[1] to Leave.RAN_OUT, q[2] to Leave.RAN_OUT, q[3] to Leave.SKIPPED, q[4] to Leave.RAN_OUT, q[5] to null)
        val related = (1..12).map { song("R$it", artist = "Related $it") }
        val sources = listOf(AdaptivePass.Source("X4", related), AdaptivePass.Source("X2", related))
        val first = AdaptivePass.run(input(h, q, AdaptivePass.Kind.ORDINARY, sources))
        val after = applied(q, first)
        val second = AdaptivePass.run(input(h, after, AdaptivePass.Kind.ORDINARY, sources))
        val placed = first.plan.replaced.map { it.pick.id }.toSet()
        assertTrue(second.slots.filter { it.id in placed }.none { it.unwanted != null || it.byWords || it.byScore != null })
    }

    // ---- The log.

    @Test
    fun `the decision lines name ids only, in the shape the phone's log is counted by`() {
        val slot = QueuePlanner.Slot(9, "old", "a", -0.82, byScore = "skipped artist")
        val pick = QueuePlanner.Pick("new", "seed", "b", 0.41)
        assertEquals(
            "Adaptive queue: replaced #9 old (skipped artist, fit -0.82) with new (fit 0.41, from seed, new to this listener)",
            AdaptivePass.replacedLine(slot, pick, heardBefore = false),
        )
        assertEquals(
            "Adaptive queue: dropped #15 x (lasting dislike), no pick good enough",
            AdaptivePass.droppedLine(QueuePlanner.Slot(15, "x", null, 0.0, unwanted = QueuePlanner.Unwanted.LASTING_DISLIKE)),
        )
        assertEquals(
            "Adaptive queue: kept #11 y (out of place by its words), no pick good enough",
            AdaptivePass.keptLine(QueuePlanner.Slot(11, "y", null, 0.0, byWords = true)),
        )
        assertEquals("Adaptive queue: added new at #7 (like seed, fit 0.41, heard before)", AdaptivePass.addedLine(pick, 7, heardBefore = true))
        assertEquals("Adaptive queue: heard abc skipped at 23%", AdaptivePass.leftLine("abc", Leave.SKIPPED, 46_000, 200, pick = false))
        assertEquals("Adaptive queue: pick abc ran out", AdaptivePass.leftLine("abc", Leave.RAN_OUT, 200_000, 200, pick = true))
        val q = (0 until 20).map { song("X$it") }
        val r = AdaptivePass.run(input(heard(q[0] to Leave.RAN_OUT, q[1] to null), q, AdaptivePass.Kind.ORDINARY, emptyList()))
        assertEquals(
            "Adaptive queue: pass ordinary, 14 ahead, 5 near, 0 candidates (0 local, 0 page), 0 usable: replaced 0, dropped 0, added 0, kept 0, note unchanged",
            AdaptivePass.summaryLine(r, 0, 0, 0, 0, null),
        )
    }
}
