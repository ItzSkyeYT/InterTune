/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.EngineParams
import com.dd3boh.outertune.engine.TagFit

/**
 * Which songs ahead in a radio make way, and what takes their place: one for one, in the same
 * place, by a fresh pick like the songs the listener kept or liked.
 *
 * The tail is every place past the locked head (AdaptiveQueue.tailStart), its first [near] places
 * the near part. Each place is one of:
 *
 * - put in or moved by hand: never touched;
 * - unwanted ([Unwanted]: an artist turned down in this run, a version of a song skipped, banned,
 *   a lasting dislike, a preview): it goes even with no pick, and before anything else;
 * - out of place by its words (AdaptiveQueue.verdict): it goes only for a pick that is no worse
 *   than what is already coming: the lowest fit among the songs of the near part that stay;
 * - out of place by its score: in the near part only, with a reason of its own against it, and
 *   only for a pick that fits by [QueueFit.REPLACE_MARGIN] more, [SCORE_CHANGES] a pass at most;
 *   after a skip, one out of place by what the skip turned from ([Slot.firm]: its artist skipped,
 *   or on the skipped song's own list) needs only a pick no worse than it, with the skip counted,
 *   and no worse than what stays;
 * - everything else, left alone.
 *
 * With no pick good enough, an unwanted song is taken out, since a gap is better than a song the
 * listener has said no to, and anything else stays, since a song that may not suit is better than
 * a gap. One pass changes at most the share of the tail it is given, and never takes the last song
 * out. The songs that stay keep their order. Picks keep apart from songs by the same artist, two of
 * an artist at most from the song playing to the end of the near part
 * ([EngineParams.maxPerArtist]), and three unplayed at most from one seed there
 * ([EngineParams.maxPerSeed]). The near part is counted as it will play, past the songs taken out.
 *
 * After a like, two of the liked song's related songs ([LIKE_PICKS]) go in at the start of the
 * tail, after any songs put in by hand that open it, before anything else is chosen, so the like
 * is answered within a few songs. They need no bar beyond what makes any pick usable: the listener
 * asked for songs like that one.
 *
 * No Android in here, so it is tested.
 */
object QueuePlanner {

    /** Picks put in after a like. */
    const val LIKE_PICKS = 2

    /** Songs one pass replaces for their score alone. */
    const val SCORE_CHANGES = 2

    /** Why a song ahead is not wanted, in the words the log uses. */
    enum class Unwanted(val why: String) {
        ARTIST_TURNED_DOWN("artist turned down"),
        SONG_TURNED_DOWN("a version of a song skipped"),
        BANNED("banned"),
        LASTING_DISLIKE("lasting dislike"),
        PREVIEW("a preview"),
    }

    /**
     * One place of the tail: where it is in play order ([index]), its song and first artist (lower
     * case), how well it fits ([fit], QueueFit), and how it is read. [byScore] names the reason a
     * place in the near part is out of place by its score, null when it is not. [firm] when that
     * reason is what a skip just turned from, on the pass after it: the listener has said it, so a
     * pick no worse than the song takes its place without the margin.
     */
    data class Slot(
        val index: Int,
        val id: String,
        val artist: String?,
        val fit: Double,
        val byHand: Boolean = false,
        val unwanted: Unwanted? = null,
        val byWords: Boolean = false,
        val byScore: String? = null,
        val firm: Boolean = false,
    ) {
        /** Out of place for its score and for nothing else. */
        val scoreOnly: Boolean get() = unwanted == null && !byWords && byScore != null
    }

    /** A usable fresh pick: the song, the seed whose list it came from, its first artist (lower case) and fit. */
    data class Pick(val id: String, val seed: String, val artist: String?, val fit: Double)

    sealed interface Change { val slot: Slot }
    data class Replace(override val slot: Slot, val pick: Pick) : Change
    data class Drop(override val slot: Slot) : Change
    /** A place that should have changed and stays, with no pick good enough. */
    data class Keep(override val slot: Slot) : Change

    /**
     * What one pass does: [changes] to places of the tail, and [inserts] put in at [insertAt], the
     * tail's first place not put in by hand, after a like. [bar] is what a pick had to reach to
     * take the place of a song a rule decided on: the lowest fit among the songs of the near part
     * that stay.
     */
    data class Plan(val changes: List<Change>, val inserts: List<Pick>, val insertAt: Int, val bar: Double) {
        val replaced: List<Replace> get() = changes.filterIsInstance<Replace>()
        val dropped: List<Drop> get() = changes.filterIsInstance<Drop>()
        val keptWithoutPick: List<Keep> get() = changes.filterIsInstance<Keep>()
        val changed: Boolean get() = replaced.isNotEmpty() || dropped.isNotEmpty() || inserts.isNotEmpty()
    }

    /**
     * [slots] is the tail in play order, [head] the first artists of every song from the one
     * playing to the start of the tail, [picks] the usable candidates best first. [liked] is the
     * song whose picks wait to go in, null with none. [picksBySeed] counts this run's unplayed picks
     * per seed from the song playing to the end of the near part. [share] is how much of the tail
     * the pass may change.
     */
    fun plan(
        slots: List<Slot>,
        head: List<String?>,
        picks: List<Pick>,
        share: Double,
        liked: String? = null,
        picksBySeed: Map<String, Int> = emptyMap(),
        near: Int = TagFit.WINDOW,
        maxPerArtist: Int = EngineParams.DEFAULT.maxPerArtist,
        maxPerSeed: Int = EngineParams.DEFAULT.maxPerSeed,
    ): Plan {
        if (slots.isEmpty()) return Plan(emptyList(), emptyList(), -1, Double.NEGATIVE_INFINITY)
        // Never worse than what is already coming: the songs of the near part that stay, not
        // those a rule or a score is about to look at again, nor those put in by hand.
        val bar = slots.take(near).filter { !it.byHand && it.unwanted == null && !it.byWords && it.byScore == null }
            .minOfOrNull { it.fit } ?: Double.NEGATIVE_INFINITY
        val pool = picks.toMutableList()
        val bySeed = HashMap(picksBySeed)
        // The arrangement as it is being changed: the first artist of each place from the song
        // playing on, as the places will play. A song taken out is gone from it, so the near part
        // is always the first places after the head that will really be heard next: with a null
        // left in its place, the songs behind it moved into the near part unseen.
        val arrangement = ArrayList<String?>(head.size + slots.size + LIKE_PICKS)
        arrangement.addAll(head)
        slots.mapTo(arrangement) { it.artist }
        val window = head.size + near

        // Whether a song by [artist] may go at [at]: put in there, pushing the rest along, or in
        // place of the song there. Never beside a song by the same artist, and within the near
        // part not a third of one artist.
        fun fits(artist: String?, at: Int, inserting: Boolean): Boolean {
            if (artist == null) return true
            val before = (at - 1 downTo 0).firstNotNullOfOrNull { arrangement[it] }
            val after = ((if (inserting) at else at + 1) until arrangement.size).firstNotNullOfOrNull { arrangement[it] }
            if (before == artist || after == artist) return false
            if (at >= window) return true
            val inWindow = (0 until minOf(window, arrangement.size)).count { j -> arrangement[j] == artist && (inserting || j != at) }
            return inWindow < maxPerArtist
        }

        // Variety where it is heard soon: at most [maxPerSeed] unplayed picks of one seed in the
        // near part, as a row of cards holds at most that many from one seed. Further on a pick
        // only ever takes a place a rule decided on, which goes without one otherwise: early in a
        // radio there is one seed, and with the cap on the whole tail an artist turned down lost
        // three songs to picks and the other nine to gaps.
        fun seedRoom(p: Pick, at: Int) = at >= window || (bySeed[p.seed] ?: 0) < maxPerSeed
        fun counted(p: Pick, at: Int) { if (at < window) bySeed[p.seed] = (bySeed[p.seed] ?: 0) + 1 }

        // After a like, its picks first: what the listener just said is answered before anything
        // the pass works out for itself takes the best picks. They go in after the songs put in
        // by hand that open the tail, which play as the listener put them: a block of Play next
        // was split in two.
        val lead = slots.takeWhile { it.byHand }.size
        val insertAt = slots.getOrNull(lead)?.index ?: (slots.last().index + 1)
        val inserts = ArrayList<Pick>()
        if (liked != null) {
            for (p in pool.filter { it.seed == liked }) {
                if (inserts.size >= LIKE_PICKS) break
                val at = head.size + lead + inserts.size
                if (!seedRoom(p, at) || !fits(p.artist, at, inserting = true)) continue
                inserts += p
                arrangement.add(at, p.artist)
                counted(p, at)
            }
            pool.removeAll(inserts)
        }

        val budget = (slots.size * share).toInt()
        val order = slots.indices.filter { !slots[it].byHand && slots[it].unwanted != null } +
            slots.indices.filter { !slots[it].byHand && slots[it].unwanted == null && (slots[it].byWords || slots[it].byScore != null) }
        val changes = ArrayList<Change>()
        // The places taken out so far. Only unwanted places are, and those are decided first and
        // nearest first, so a place's spot in the arrangement is settled when it is decided.
        val dropped = ArrayList<Int>()
        fun spot(i: Int) = head.size + i + (if (i >= lead) inserts.size else 0) - dropped.count { it < i }
        var made = 0
        var byScore = 0
        for (i in order) {
            if (made >= budget) break
            val slot = slots[i]
            if (slot.scoreOnly && byScore >= SCORE_CHANGES) continue
            val at = spot(i)
            val need = when {
                slot.scoreOnly && slot.firm -> maxOf(bar, slot.fit)
                slot.scoreOnly -> slot.fit + QueueFit.REPLACE_MARGIN
                else -> bar
            }
            val pick = pool.firstOrNull { it.fit >= need && seedRoom(it, at) && fits(it.artist, at, inserting = false) }
            when {
                pick != null -> {
                    changes += Replace(slot, pick)
                    pool.remove(pick)
                    arrangement[at] = pick.artist
                    counted(pick, at)
                    made++
                    if (slot.scoreOnly) byScore++
                }
                // Never the last song ahead: the tail is never emptied.
                slot.unwanted != null && slots.size - (dropped.size + 1) >= 1 -> {
                    changes += Drop(slot)
                    arrangement.removeAt(at)
                    dropped += i
                    made++
                }
                else -> changes += Keep(slot)
            }
        }
        return Plan(changes, inserts, insertAt, bar)
    }
}
