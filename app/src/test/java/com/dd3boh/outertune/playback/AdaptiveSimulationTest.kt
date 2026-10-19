/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.AdaptiveQueue.Leave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Long radio sessions played out on the JVM with the real pieces, Heard, AdaptivePass, the planner
 * and applyAdaptiveEdits, the way MusicService strings them together, with random skips, keeps,
 * likes, songs put in by hand and the radio's own top-up, shuffled and not. After every pass the
 * rules the listener was promised must still hold: the song playing and the locked songs after it
 * never change, the board and the player stay in step, nothing plays twice, nothing put in by hand
 * goes, a pass changes no more than its share and never empties what is ahead.
 */
class AdaptiveSimulationTest {

    private class Player(songs: List<MediaMetadata>) : EditablePlayer {
        val items = songs.toMutableList()
        override fun uidAt(index: Int) = items.getOrNull(index)?.composeUidWorkaround
        override fun replace(index: Int, song: MediaMetadata) { items[index] = song }
        override fun remove(index: Int) { items.removeAt(index) }
        override fun add(index: Int, songs: List<MediaMetadata>) { items.addAll(index, songs) }
    }

    private fun run(seed: Int, shuffled: Boolean) {
        val rnd = Random(seed)
        val treatments = listOf("", "", "", " (Slowed)", " (Sped Up)", " (Nightcore)", " (Remix)", " (Live)")
        val catalog = (0 until 400).map { i ->
            MediaMetadata(
                id = "c$i", title = "Song $i" + treatments[rnd.nextInt(treatments.size)],
                artists = listOf(MediaMetadata.Artist(null, "Artist ${i % 40}")), duration = 200, genre = null,
            )
        }
        val byId = catalog.associateBy { it.id }
        val related = catalog.associate { s -> s.id to (0 until 15).map { catalog[rnd.nextInt(catalog.size)] }.distinctBy { it.id }.filter { it.id != s.id } }

        val start = catalog.shuffled(rnd).take(30)
        val songs = start.mapIndexed { i, s -> s.copy(composeUidWorkaround = rnd.nextDouble(), shuffleIndex = i) }.toMutableList()
        if (shuffled) {
            val order = songs.indices.shuffled(rnd)
            songs.forEachIndexed { i, s -> s.shuffleIndex = order[i] }
        }
        val q = MultiQueueObject(id = 1, title = "Radio", queue = songs, shuffled = shuffled, queuePos = songs.indexOfFirst { it.shuffleIndex == 0 }, index = 0, playlistId = "seed", runId = 9)
        val player = Player(q.getCurrentQueueShuffled())
        var now = 1_000_000_000L
        val heard = AdaptiveQueue.Heard(clock = { now })
        val taste = QueueFit.Taste.empty(now)
        fun play(i: Int) {
            val s = player.items[i]
            heard.note(1L, s.id, s.title, s.artists.map { it.name }, s.duration, runId = 9L, radio = true)
            q.setCurrentQueuePos(i)
        }
        var current = 0
        play(current)
        var passes = 0
        repeat(150) {
            now += 60_000L
            // How the song playing is left, and what happens meanwhile.
            val roll = rnd.nextInt(100)
            if (roll < 10) heard.liked(player.items[current].id)
            if (roll in 10..14 && current + 2 < player.items.size) {
                // Play next, by hand.
                val extra = catalog[rnd.nextInt(catalog.size)].takeIf { c -> player.items.none { it.id == c.id } }
                if (extra != null) {
                    heard.putInByHand(1L, 9L, listOf(extra.id), radio = true)
                    q.insertAtPlayIndex(current + 1, listOf(extra))
                    player.items.add(current + 1, q.getCurrentQueueShuffled()[current + 1])
                }
            }
            if (roll in 15..17 && current + 3 < player.items.size) {
                // A song ahead swiped out of the sheet: board first, then the player, as Queue.kt does.
                val i = current + 1 + rnd.nextInt(player.items.size - current - 1)
                val gone = player.items[i]
                if (q.removeAtPlayIndex(i, current)) {
                    player.items.removeAt(i)
                    heard.removedByHand(gone.id, gone.title, gone.artists.map { it.name })
                }
            }
            if (roll in 18..19 && current + 3 < player.items.size) {
                // A song ahead dragged two places on.
                val from = current + 2
                val moved = player.items[from]
                if (from + 1 < player.items.size) {
                    val reordered = q.getCurrentQueueShuffled().toMutableList().also { it.add(from + 1, it.removeAt(from)) }
                    if (q.shuffled) reordered.forEachIndexed { k, song -> song.shuffleIndex = k } else { q.queue.clear(); q.queue.addAll(reordered) }
                    player.items.clear()
                    player.items.addAll(q.getCurrentQueueShuffled())
                    heard.movedByHand(moved.id)
                }
            }
            val back = roll in 20..24 && current > 0
            val how = if (back) Leave.NEITHER else when (rnd.nextInt(10)) { in 0..4 -> Leave.SKIPPED; in 5..6 -> Leave.KEPT; else -> Leave.RAN_OUT }
            heard.left(how)
            if (current + 1 >= player.items.size) return@repeat
            if (back) current-- else current++
            play(current)
            // The radio's top-up, as MusicService does it: only what is new to the run.
            if (player.items.size - current <= 5) {
                val page = related.getValue(player.items[current].id)
                val fresh = RadioPage.fresh(page, q.getCurrentQueueShuffled(), heard.reached, heard.reachedVersions)
                if (fresh.isNotEmpty()) {
                    q.insertAtPlayIndex(Int.MAX_VALUE, fresh)
                    player.items.clear()
                    player.items.addAll(q.getCurrentQueueShuffled())
                }
            }
            // The pass, as MusicService.adaptiveSnapshot, computePass and applyPass string it.
            val view = heard.view()
            val kind = when {
                view.likePending != null -> AdaptivePass.Kind.LIKE
                how == Leave.SKIPPED -> AdaptivePass.Kind.SKIP
                else -> AdaptivePass.Kind.ORDINARY
            }
            val locked = if (kind == AdaptivePass.Kind.ORDINARY) AdaptiveQueue.LOCKED else AdaptiveQueue.STRONG_LOCKED
            val all = player.items.toList()
            val settled = AdaptiveQueue.settledThrough(current, all, { it.id }, heard.reached)
            val tailStart = AdaptiveQueue.tailStart(settled, all.size, locked) ?: return@repeat
            val sources = (listOfNotNull(view.likePending) + view.seeds).distinct().map { AdaptivePass.Source(it, related[it] ?: emptyList()) }
            val result = AdaptivePass.run(
                AdaptivePass.Input(
                    kind = kind,
                    tail = (tailStart until all.size).map { AdaptivePass.Place(it, all[it], all[it].id in view.byHand) },
                    head = all.subList(current, tailStart),
                    queue = all,
                    view = view,
                    taste = taste,
                    sources = sources,
                    skippedRelated = view.lastSkipped?.takeIf { kind == AdaptivePass.Kind.SKIP }?.let { id -> related[id].orEmpty().map { it.id }.toSet() }.orEmpty(),
                ),
            )
            val plan = result.plan
            val edits = ArrayList<AdaptiveEdit>()
            for (c in plan.changes) when (c) {
                is QueuePlanner.Replace -> edits += AdaptiveEdit.Replace(c.slot.index, all[c.slot.index].composeUidWorkaround, result.songs.getValue(c.pick.id))
                is QueuePlanner.Drop -> edits += AdaptiveEdit.Drop(c.slot.index, all[c.slot.index].composeUidWorkaround)
                is QueuePlanner.Keep -> {}
            }
            if (plan.inserts.isNotEmpty()) edits += AdaptiveEdit.Insert(plan.insertAt, all[plan.insertAt - 1].composeUidWorkaround, plan.inserts.map { result.songs.getValue(it.id) })

            val before = all.map { it.id }
            val done = applyAdaptiveEdits(q, player, edits, notBefore = tailStart, current = current)
            passes++
            val after = player.items.map { it.id }

            assertEquals("the board and the player in step", q.getCurrentQueueShuffled().map { it.id }, after)
            assertEquals("the song playing stays current", before[current], q.getCurrentQueueShuffled()[q.getQueuePosShuffled()].id)
            assertEquals("nothing up to the end of the lock changes", before.subList(0, tailStart), after.subList(0, tailStart))
            val twice = after.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue(
                "nothing plays twice: $twice, before ${before.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}, " +
                    "picks ${plan.replaced.map { it.pick.id }} inserts ${plan.inserts.map { it.id }} kind $kind seed $seed shuffled $shuffled",
                twice.isEmpty(),
            )
            for (id in view.byHand) if (id in before) assertTrue("a song put in by hand stays", id in after)
            val changed = done.count { it !is AdaptiveEdit.Insert }
            assertTrue("no more than the share", changed <= ((before.size - tailStart) * (if (kind == AdaptivePass.Kind.ORDINARY) AdaptiveQueue.MAX_DROP_SHARE else AdaptiveQueue.STRONG_DROP_SHARE)).toInt())
            assertTrue("never empties what is ahead", after.size > tailStart)
            assertTrue("no pick is by an artist turned down", plan.replaced.none { it.pick.artist in view.turnedDown.artists })
            assertTrue("no pick was taken out before", (plan.replaced.map { it.pick.id } + plan.inserts.map { it.id }).none { it in view.dismissed })

            // What applyPass notes in the run afterwards.
            val picked = LinkedHashMap<String, String>()
            plan.replaced.forEach { picked[it.pick.id] = it.pick.seed }
            plan.inserts.forEach { picked[it.id] = it.seed }
            heard.picked(picked)
            heard.dismiss((plan.replaced.map { it.slot.id } + plan.dropped.map { it.slot.id }).filter { it in view.picks })
            view.likePending?.let { if (plan.inserts.isNotEmpty()) heard.likeAnswered(it) }
            assertTrue(byId.keys.containsAll(after))
        }
        assertTrue("the session ran its passes", passes > 50)
    }

    @Test
    fun `long sessions keep every promise, shuffled or not`() {
        for (seed in 1..12) {
            run(seed, shuffled = false)
            run(seed, shuffled = true)
        }
    }
}
