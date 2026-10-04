/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import kotlin.random.Random

/** What the heading over Quick picks says under a lean. */
enum class LeanHeadingKind {
    /** The lean placed every card it was given. */
    LEANING,
    /** It ran short: only some of its cards, the usual mix filling the rest. */
    SHORT,
    /** It had nothing, or too little for a row, so the row is the usual mix for now: Auto's. */
    NOTHING,
}

/**
 * What Home shows of a lean, for the heading and Why these?: the lean the row was built under,
 * the one it followed, and how many of its cards made it to the screen.
 */
data class LeanOnScreen(
    /** The choice in the preferences when the row was built. */
    val stored: Lean,
    /** What the build followed, or was to follow when it [gaveWay]; AUTO when a chip or New songs only set the choice aside. */
    val applied: Lean,
    /** The lead lane's quota and how many of its cards the engine placed. */
    val quota: Int,
    val placed: Int,
    /** The lead lane's cards in the row on screen, and the row's length. */
    val onScreen: Int,
    val shown: Int,
    /** Playing now built with no session under way: when the latest one ended, for "what you played last". */
    val lastSessionEndedAt: Long? = null,
    /** The lean had too little to give and the row on screen is Auto's, see [BuiltRow.gaveWay]. */
    val gaveWay: LeanGaveWay? = null,
) {
    val heading: LeanHeadingKind? get() = if (gaveWay != null) LeanHeadingKind.NOTHING else LeanRow.heading(applied, quota, placed, onScreen)

    /**
     * Whether "nothing yet" can be said in Forgotten's own words, which name the 45 days: only
     * when its lane really had nothing, not when it had a few songs and too short a row.
     */
    val forgottenHasNothing: Boolean get() = applied == Lean.FORGOTTEN && gaveWay?.nothing == true
}

/**
 * The lean's rules on the Home side, kept here as plain functions so the JVM tests cover them:
 * how the tidy pass keeps a lean's cards in place, what the heading says, what a pull to refresh
 * sets aside, when Playing now follows the session, and how much of Try both's mix a lean gets.
 */
object LeanRow {
    /**
     * The heading's kind. "Short" and "nothing" are only said when the engine itself placed fewer
     * lead cards than its quota, counting what is on screen; a single card lost to the tidy pass
     * is not announced. Null with no lean applied.
     */
    fun heading(applied: Lean, quota: Int, placed: Int, onScreen: Int): LeanHeadingKind? = when {
        applied == Lean.AUTO || applied.lane == null -> null
        placed >= quota -> LeanHeadingKind.LEANING
        onScreen <= 0 -> LeanHeadingKind.NOTHING
        else -> LeanHeadingKind.SHORT
    }

    /**
     * The row on screen under a lean, from what the tidy pass kept of the engine's cards and pool.
     *
     * The pass drops cards (just played, a ban, a preview, the artist cap) and the ordinary row
     * closes the gap with the pool's best, whatever its lane, which under a lean would wash the
     * lean out of the first column. So a lead card the pass dropped is replaced in its own place
     * by the next lead card that passed (the spares included), and any other card by the next
     * other one, each falling back to the other kind when its own runs out. Then the first column
     * is filled from the first [columns] lead cards, one an artist, as the engine placed it.
     *
     * [cards] are the engine's cards in slot order, [tidied] what the pass kept of the cards and
     * the pool, in that order. Only for the engine's own row: Try both drafts its mix from the
     * engine's cards and never comes here.
     */
    fun <T> compose(
        cards: List<T>,
        tidied: List<T>,
        lead: Lane,
        rowSize: Int,
        columns: Int,
        id: (T) -> String,
        lane: (T) -> Lane?,
        artist: (T) -> String?,
    ): List<T> {
        val kept = tidied.mapTo(HashSet()) { id(it) }
        val cardIds = cards.mapTo(HashSet()) { id(it) }
        val fromPool = tidied.filter { id(it) !in cardIds }
        val leadQueue = ArrayDeque(fromPool.filter { lane(it) == lead })
        val otherQueue = ArrayDeque(fromPool.filter { lane(it) != lead })
        val composed = ArrayList<T>()
        val used = HashSet<String>()
        for (c in cards) {
            val next = if (id(c) in kept) c
                else if (lane(c) == lead) leadQueue.removeFirstOrNull() ?: otherQueue.removeFirstOrNull()
                else otherQueue.removeFirstOrNull() ?: leadQueue.removeFirstOrNull()
            if (next != null && used.add(id(next))) composed += next
        }
        for (c in fromPool) { if (composed.size >= rowSize) break; if (used.add(id(c))) composed += c }
        val column = ArrayList<T>()
        val columnArtists = HashSet<String>()
        for (c in composed) {
            if (column.size == columns) break
            val a = artist(c)?.trim()?.lowercase()
            if (lane(c) == lead && (a.isNullOrEmpty() || columnArtists.add(a))) column += c
        }
        val inColumn = column.mapTo(HashSet()) { id(it) }
        return (column + composed.filter { id(it) !in inColumn }).take(rowSize)
    }

    /**
     * What a pull to refresh keeps out of the next build. Every card of the standing row, as it
     * always was, except under Playing now, where the lead cards outside the first column may come
     * back: the session has only so many related songs, and banning all twelve left the new row
     * with a third of them.
     */
    fun pullBanned(cards: List<Card>, applied: Lean, columns: Int): Set<String> {
        if (applied != Lean.SIMILAR) return cards.mapTo(HashSet()) { it.songId }
        val lead = applied.lane
        return cards.filterIndexed { slot, c -> slot < columns || c.lane != lead }.mapTo(HashSet()) { it.songId }
    }

    /**
     * Listens that say the session has moved on since the row was built: heard well, by the same
     * rule as everywhere else (so a song of unknown length needs two minutes, and a like during it
     * counts), finished rather than still playing, and not started from Quick picks or its widget
     * at any depth, so playing the row itself never moves the row. A play that failed is not the
     * listener choosing the song, however much of it played first (see [EngineListens]).
     */
    fun heardWellSince(listens: List<ListenRow>, likedAt: (String) -> Long?, p: EngineParams = EngineParams.DEFAULT): Int =
        listens.count { l ->
            l.learn && l.endReason != EndReason.OPEN && !EngineListens.failed(l) &&
                l.origin != PlayOrigin.QUICK_PICKS.code && l.origin != PlayOrigin.WIDGET.code &&
                Signals.engagement(l, likedAt(l.songId), p) >= p.justPlayedEngagement
        }

    /** Whether Playing now's row should be built again as Home comes back into view. */
    fun similarRebuildDue(applied: Lean, heardWell: Int, p: EngineParams = EngineParams.DEFAULT): Boolean =
        applied == Lean.SIMILAR && p.leanSimilarRebuildListens > 0 && heardWell >= p.leanSimilarRebuildListens

    /**
     * Whether the engine's cached input is behind the listen log: a listen has started since the
     * newest one it holds. Playing now follows the session, so it reads the log again rather than
     * build from one that is minutes old.
     */
    fun inputBehind(latestListenStart: Long?, newestCached: Long?): Boolean =
        latestListenStart != null && (newestCached == null || latestListenStart > newestCached)

    /**
     * Try both: how many of a lean's cards the drafted mix holds, as (of the engine's first ten,
     * of the mix's first four). The draft alternates the two sources card by card, so the engine
     * gives the first ten cards of twenty and two of the first four. Replays the assembly's slot
     * order over lanes that never run short, which is the most the lean can have there.
     */
    fun compareShare(lean: Lean, dial: Double, p: EngineParams = EngineParams.DEFAULT): Pair<Int, Int> {
        val lead = lean.lane ?: return 0 to 0
        val q = quotas(p.rowSize, dial, false, p, lean)
        // Every lane full, every card its own artist and group, so only the slot order decides.
        val lanes = Lane.entries.associateWith { l ->
            List(p.rowSize * 4) { i -> Candidate("${l.name}$i", l, DoubleArray(Features.COUNT), -i.toDouble(), null, "${l.name}$i", "${l.name}$i") }
        }
        val placed = Assembly(lanes, q, Weights.PRIORS, p, Random(0), null, lead, p.maxPerArtist).run()
        val engineCards = p.rowSize / 2
        return placed.take(engineCards).count { it.first.lane == lead } to placed.take(2).count { it.first.lane == lead }
    }
}
