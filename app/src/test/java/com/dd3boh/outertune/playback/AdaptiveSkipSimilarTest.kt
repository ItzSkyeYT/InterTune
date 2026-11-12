/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.AdaptiveQueue.Leave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The adaptive queue reads a skip by what the skipped song is like (Unreleased.ADAPTIVE_SIMILAR, his idea of
 * 9 Nov 2026): the songs ahead closer to a song skipped than to any song kept make way, and a song like two
 * skipped songs goes wherever it is. Without the flag, the stored list of the song just skipped, as before.
 */
class AdaptiveSkipSimilarTest {

    private val now = 1_000L * 86_400_000L
    private val empty = QueueFit.Taste.empty(now)

    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") =
        MediaMetadata(id = id, title = title, artists = listOf(MediaMetadata.Artist(null, artist)), duration = 200, genre = null)

    /** What the lists hold of a song: its version, as MusicService.skippedLists gives them. */
    private fun key(id: String, artist: String = "Artist $id") = SkipSimilar.keyOf(song(id, artist))

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
        skippedLists: List<List<String>> = emptyList(),
        skippedRelated: Set<String> = emptySet(),
        similar: Boolean,
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
            taste = empty,
            sources = sources,
            skippedRelated = skippedRelated,
            skippedLists = skippedLists,
            similar = similar,
        )
    }

    // ---- the run's skipped songs

    @Test
    fun `the run's skipped songs are newest first, without one gone back to or liked, three at most`() {
        val s = (0 until 8).map { song("s$it") }
        val h = heard(
            s[0] to Leave.SKIPPED, s[1] to Leave.RAN_OUT, s[2] to Leave.SKIPPED, s[3] to Leave.SKIPPED,
            s[4] to Leave.SKIPPED, s[5] to Leave.SKIPPED, s[6] to null,
        )
        assertEquals(listOf("s5", "s4", "s3"), h.view().skippedIds)
        // Going back to s5 is asking for it: it is no longer a song turned from.
        h.left(Leave.NEITHER)
        h.note(1L, "s5", "Song s5", listOf("Artist s5"), 200, runId = 5L, radio = true)
        assertEquals(listOf("s4", "s3", "s2"), h.view().skippedIds)
    }

    @Test
    fun `a song liked and then left early is not one turned from`() {
        val a = song("a")
        val h = heard(song("k") to Leave.RAN_OUT, a to null)
        h.liked("a")
        h.left(Leave.SKIPPED)
        h.note(1L, "n", "Song n", listOf("Artist n"), 200, runId = 5L, radio = true)
        assertEquals(emptyList<String>(), h.view().skippedIds)
    }

    // ---- the pass

    /** Kept k0, k1, skipped x, playing p; a queue ahead of near and far songs. */
    private fun scene(): Pair<AdaptiveQueue.Heard, List<MediaMetadata>> {
        val k0 = song("k0"); val k1 = song("k1"); val x = song("x"); val p = song("p")
        val h = heard(k0 to Leave.RAN_OUT, k1 to Leave.RAN_OUT, x to Leave.SKIPPED, p to null)
        val ahead = (0 until 14).map { song("q$it") }
        return h to listOf(k0, k1, x, p) + ahead
    }

    @Test
    fun `with the flag, a song near ahead closer to the skip than to anything kept makes way, one also close to a kept song stays`() {
        val (h, queue) = scene()
        val tail = input(h, queue, AdaptivePass.Kind.SKIP, emptyList(), similar = true).tail.map { it.song.id }
        val nearOnlyLikeSkip = tail[0]
        val nearAlsoKept = tail[1]
        val skippedList = listOf(key(nearOnlyLikeSkip), key("other"), key(nearAlsoKept))
        val keptList = listOf(nearAlsoKept) + (1..12).map { "pick$it" }
        val sources = listOf(AdaptivePass.Source("k1", keptList.map { song(it) }))
        val r = AdaptivePass.run(input(h, queue, AdaptivePass.Kind.SKIP, sources, skippedLists = listOf(skippedList), similar = true))
        val bySong = r.slots.associateBy { it.id }
        assertEquals("like the song skipped", bySong.getValue(nearOnlyLikeSkip).byScore)
        assertTrue(bySong.getValue(nearOnlyLikeSkip).firm)
        assertEquals(null, bySong.getValue(nearAlsoKept).byScore)
        assertTrue(r.plan.replaced.any { it.slot.id == nearOnlyLikeSkip })
        assertFalse(r.plan.replaced.any { it.slot.id == nearAlsoKept })
    }

    @Test
    fun `without the flag, the stored list of the song just skipped decides, as before`() {
        val (h, queue) = scene()
        val tail = input(h, queue, AdaptivePass.Kind.SKIP, emptyList(), similar = false).tail.map { it.song.id }
        val sources = listOf(AdaptivePass.Source("k1", (1..12).map { song("pick$it", artist = "Kept $it") }))
        val r = AdaptivePass.run(
            input(h, queue, AdaptivePass.Kind.SKIP, sources, skippedLists = listOf(listOf(tail[2])), skippedRelated = setOf(tail[0], tail[1]), similar = false),
        )
        val bySong = r.slots.associateBy { it.id }
        assertEquals("like the song skipped", bySong.getValue(tail[0]).byScore)
        assertEquals("like the song skipped", bySong.getValue(tail[1]).byScore)
        // the lists the flag reads are not read without it
        assertEquals(null, bySong.getValue(tail[2]).byScore)
        assertTrue(r.slots.none { it.unwanted == QueuePlanner.Unwanted.LIKE_SONGS_SKIPPED })
    }

    @Test
    fun `a song like two skipped songs goes, even far ahead, on an ordinary pass`() {
        val (h, queue) = scene()
        val tail = input(h, queue, AdaptivePass.Kind.ORDINARY, emptyList(), similar = true).tail.map { it.song.id }
        val far = tail.last()
        val sources = listOf(AdaptivePass.Source("k1", (1..12).map { song("pick$it", artist = "Kept $it") }))
        val r = AdaptivePass.run(
            input(h, queue, AdaptivePass.Kind.ORDINARY, sources, skippedLists = listOf(listOf(key("a"), key(far)), listOf(key(far), key("b"))), similar = true),
        )
        assertEquals(QueuePlanner.Unwanted.LIKE_SONGS_SKIPPED, r.slots.single { it.id == far }.unwanted)
        assertTrue(r.plan.replaced.any { it.slot.id == far } || r.plan.dropped.any { it.slot.id == far })
        // Away from a skip, one skipped list alone takes nothing out.
        assertTrue(r.slots.none { it.byScore == "like the song skipped" })
    }

    @Test
    fun `another upload of a song ahead, on a skipped song's list, counts as that song`() {
        // The emulator, 9 Nov 2026: the related lists named the radio's songs by other uploads, so by id
        // nothing was ever like a skip. By version, the music video on the list is the song in the queue.
        val (h, queue) = scene()
        val ahead = input(h, queue, AdaptivePass.Kind.SKIP, emptyList(), similar = true).tail.first().song
        val otherUpload = song("${ahead.id}-video", artist = ahead.artists.first().name, title = "${ahead.title} (Official Video)")
        assertEquals(SkipSimilar.keyOf(ahead), SkipSimilar.keyOf(otherUpload))
        val sources = listOf(AdaptivePass.Source("k1", (1..12).map { song("pick$it", artist = "Kept $it") }))
        val r = AdaptivePass.run(input(h, queue, AdaptivePass.Kind.SKIP, sources, skippedLists = listOf(listOf(SkipSimilar.keyOf(otherUpload))), similar = true))
        assertEquals("like the song skipped", r.slots.single { it.id == ahead.id }.byScore)
        // Without the flag, as before: by id, the other upload is not the song.
        val before = AdaptivePass.run(input(h, queue, AdaptivePass.Kind.SKIP, sources, skippedRelated = setOf(otherUpload.id), similar = false))
        assertEquals(null, before.slots.single { it.id == ahead.id }.byScore)
    }

    @Test
    fun `a pick closer to a skipped song than to its own seed is not picked`() {
        val (h, queue) = scene()
        val sources = listOf(AdaptivePass.Source("k1", (0 until 12).map { song("pick$it", artist = "Kept $it") }))
        // pick11 is last on its seed's list and first on the skipped song's: like the skip.
        val r = AdaptivePass.run(input(h, queue, AdaptivePass.Kind.SKIP, sources, skippedLists = listOf(listOf(key("pick11", "Kept 11"), key("pick0", "Kept 0"))), similar = true))
        assertFalse("pick11" in r.songs)
        // pick0 is first on both: as close to what is kept, so it may still be picked.
        assertTrue("pick0" in r.songs)
    }

    // ---- the service

    @Test
    fun `the service asks for a skipped song's page only behind the flag, online, never throttled, once a song, spaced`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("val similar = Unreleased.ADAPTIVE_SIMILAR" in service)
        assertTrue("val skippedLists = if (similar) skippedLists(snap) else emptyList()" in service)
        assertTrue("skippedLists = skippedLists, similar = similar" in service)
        val page = service.substringAfter("private suspend fun skipPage(").substringBefore("\n    }\n")
        assertTrue(page, "if (!snap.online) return noPage(\"offline\")" in page)
        assertTrue(page, "if (Throttle.isBlocked) return noPage(\"throttled\")" in page)
        assertTrue(page, "< SKIP_PAGE_EVERY_MS) return noPage(\"too soon\")" in page)
        assertTrue(page, "if (!skipPagesAsked.getOrPut(key) { HashSet() }.add(id)) return noPage(\"asked already\")" in page)
        // only the song just skipped, only on the pass after its skip, and only when nothing is held for it
        val lists = service.substringAfter("private suspend fun skippedLists(").substringBefore("\n    }\n")
        assertTrue(lists, "?: if (snap.ask.skip && id == snap.view.lastSkipped) skipPage(id, snap)" in lists)
        // every list by version, as the pass reads the seeds' lists
        assertTrue(lists, "?.map(SkipSimilar::keyOf)" in lists)
        assertTrue("val ADAPTIVE_SIMILAR = BuildConfig.PREVIEW_FEATURES" in File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText())
    }

    // ---- what it does over long sessions

    private class Player(songs: List<MediaMetadata>) : EditablePlayer {
        val items = songs.toMutableList()
        override fun uidAt(index: Int) = items.getOrNull(index)?.composeUidWorkaround
        override fun replace(index: Int, song: MediaMetadata) { items[index] = song }
        override fun remove(index: Int) { items.removeAt(index) }
        override fun add(index: Int, songs: List<MediaMetadata>) { items.addAll(index, songs) }
    }

    /** Songs of the turned-from style heard, songs of the loved styles taken out, songs heard in all. */
    private data class Tally(val disliked: Int, val lovedTakenOut: Int, val heard: Int)

    /**
     * One listener on one radio: six styles of 60 songs, six artists each; a song's related list is 16 of its
     * own style first, then 4 of others, as YouTube lists the closest first. The listener keeps styles 0 and 1,
     * skips style 2 early every time, and the other three half the time. The radio starts on a style 0 song
     * with a third of style 2 in it, and tops itself up from the song playing.
     */
    private fun session(seed: Int, similar: Boolean, missing: Double, artists: Int = 6): Tally {
        val rnd = Random(seed)
        // Which songs' lists the phone holds when they are skipped: a song skipped in its first seconds often
        // has none yet. The same coin for both runs of a seed. Without the flag a missing list is no list;
        // with it, the skipped song's page is asked for (MusicService.skipPage).
        val coin = Random(seed * 7919)
        val styles = 6
        val catalog = (0 until styles * 60).map { i -> song("c$i", artist = "Style ${i % styles} artist ${(i / styles) % artists}") }
        fun style(s: MediaMetadata) = s.id.removePrefix("c").toInt() % styles
        val byStyle = catalog.groupBy(::style)
        val related = catalog.associate { s ->
            val own = byStyle.getValue(style(s)).filter { it.id != s.id }.shuffled(rnd).take(16)
            val others = catalog.filter { style(it) != style(s) }.shuffled(rnd).take(4)
            s.id to own + others
        }
        val stored = catalog.associate { it.id to (coin.nextDouble() >= missing) }
        val start = (byStyle.getValue(0).shuffled(rnd).take(14) + byStyle.getValue(1).shuffled(rnd).take(6) + byStyle.getValue(2).shuffled(rnd).take(10))
            .shuffled(rnd)
        val songs = start.mapIndexed { i, s -> s.copy(composeUidWorkaround = rnd.nextDouble(), shuffleIndex = i) }
        val q = MultiQueueObject(id = 1, title = "Radio", queue = songs.toMutableList(), shuffled = false, queuePos = 0, index = 0, playlistId = "seed", runId = 9)
        val player = Player(q.getCurrentQueueShuffled())
        var clock = now
        val heard = AdaptiveQueue.Heard(clock = { clock })
        fun play(i: Int) {
            val s = player.items[i]
            heard.note(1L, s.id, s.title, s.artists.map { it.name }, s.duration, runId = 9L, radio = true)
            q.setCurrentQueuePos(i)
        }
        var current = 0
        play(current)
        var disliked = 0
        var lovedOut = 0
        var count = 1
        repeat(90) {
            clock += 60_000L
            val playing = player.items[current]
            val how = when (style(playing)) {
                0, 1 -> Leave.RAN_OUT
                2 -> Leave.SKIPPED
                else -> if (rnd.nextBoolean()) Leave.RAN_OUT else Leave.SKIPPED
            }
            heard.left(how)
            if (current + 1 >= player.items.size) return@repeat
            current++
            play(current)
            count++
            if (style(player.items[current]) == 2) disliked++
            if (player.items.size - current <= 5) {
                val fresh = RadioPage.fresh(related.getValue(player.items[current].id), q.getCurrentQueueShuffled(), heard.reached, heard.reachedVersions)
                if (fresh.isNotEmpty()) {
                    q.insertAtPlayIndex(Int.MAX_VALUE, fresh)
                    player.items.clear()
                    player.items.addAll(q.getCurrentQueueShuffled())
                }
            }
            val view = heard.view()
            val kind = if (how == Leave.SKIPPED) AdaptivePass.Kind.SKIP else AdaptivePass.Kind.ORDINARY
            val locked = if (kind == AdaptivePass.Kind.ORDINARY) AdaptiveQueue.LOCKED else AdaptiveQueue.STRONG_LOCKED
            val all = player.items.toList()
            val settled = AdaptiveQueue.settledThrough(current, all, { it.id }, heard.reached)
            val tailStart = AdaptiveQueue.tailStart(settled, all.size, locked) ?: return@repeat
            val sources = view.seeds.map { AdaptivePass.Source(it, related[it].orEmpty()) }
            val result = AdaptivePass.run(
                AdaptivePass.Input(
                    kind = kind,
                    tail = (tailStart until all.size).map { AdaptivePass.Place(it, all[it], all[it].id in view.byHand) },
                    head = all.subList(current, tailStart),
                    queue = all,
                    view = view,
                    taste = empty,
                    sources = sources,
                    skippedRelated = view.lastSkipped?.takeIf { kind == AdaptivePass.Kind.SKIP && stored.getValue(it) }?.let { id -> related[id].orEmpty().map { it.id }.toSet() }.orEmpty(),
                    skippedLists = if (similar) view.skippedIds.map { id -> related[id].orEmpty().map(SkipSimilar::keyOf) } else emptyList(),
                    similar = similar,
                ),
            )
            val plan = result.plan
            val edits = ArrayList<AdaptiveEdit>()
            for (c in plan.changes) when (c) {
                is QueuePlanner.Replace -> edits += AdaptiveEdit.Replace(c.slot.index, all[c.slot.index].composeUidWorkaround, result.songs.getValue(c.pick.id))
                is QueuePlanner.Drop -> edits += AdaptiveEdit.Drop(c.slot.index, all[c.slot.index].composeUidWorkaround)
                is QueuePlanner.Keep -> {}
            }
            lovedOut += (plan.replaced.map { it.slot.id } + plan.dropped.map { it.slot.id }).count { id -> style(catalog.first { it.id == id }) in 0..1 }
            if (plan.inserts.isNotEmpty()) edits += AdaptiveEdit.Insert(plan.insertAt, all[plan.insertAt - 1].composeUidWorkaround, plan.inserts.map { result.songs.getValue(it.id) })
            applyAdaptiveEdits(q, player, edits, notBefore = tailStart, current = current)
            val picked = LinkedHashMap<String, String>()
            plan.replaced.forEach { picked[it.pick.id] = it.pick.seed }
            plan.inserts.forEach { picked[it.id] = it.seed }
            heard.picked(picked)
        }
        return Tally(disliked, lovedOut, count)
    }

    @Test
    fun `over long radio sessions, reading skips by likeness takes out far fewer loved songs, and plays no more of the style turned from`() {
        for ((missing, artists) in listOf(0.0 to 6, 0.5 to 6, 0.5 to 30)) {
            var before = Tally(0, 0, 0)
            var after = Tally(0, 0, 0)
            for (seed in 1..40) {
                val b = session(seed, similar = false, missing = missing, artists = artists)
                val a = session(seed, similar = true, missing = missing, artists = artists)
                before = Tally(before.disliked + b.disliked, before.lovedTakenOut + b.lovedTakenOut, before.heard + b.heard)
                after = Tally(after.disliked + a.disliked, after.lovedTakenOut + a.lovedTakenOut, after.heard + a.heard)
            }
            println(
                "AdaptiveSkipSimilar over 40 sessions, $artists artists a style, ${(missing * 100).toInt()}% of skipped songs without a stored list: " +
                    "songs of the skipped style heard ${before.disliked} of ${before.heard} before, ${after.disliked} of ${after.heard} with it; " +
                    "loved songs taken out ${before.lovedTakenOut} before, ${after.lovedTakenOut} with it",
            )
            assertTrue("no more songs of the style turned from: ${before.disliked} -> ${after.disliked}", after.disliked <= before.disliked)
            assertTrue("fewer loved songs taken out: ${before.lovedTakenOut} -> ${after.lovedTakenOut}", after.lovedTakenOut < before.lovedTakenOut)
        }
    }

    @Test
    fun `the flag is the debug builds' only`() {
        assertEquals(com.dd3boh.outertune.BuildConfig.PREVIEW_FEATURES, Unreleased.ADAPTIVE_SIMILAR)
    }
}
