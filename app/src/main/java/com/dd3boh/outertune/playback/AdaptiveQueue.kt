/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit

/**
 * Keeps the far end of the queue honest while the near end stays put.
 *
 * Half of this listener's plays are six or more songs deep in an autoplay chain, and that band is
 * the worst engaged of the lot: radio runs at an 84 percent skip rate against 64 for Quick picks.
 * The reason is structural rather than bad luck. A radio page is fetched once, when the queue is
 * five from the end, and then played to the end whatever happens in between. Twenty minutes later
 * the listener has moved on and the queue has not.
 *
 * So the tail is provisional. Whatever has actually been playing lately decides which of the
 * songs still ahead belong there, and the ones that plainly do not are dropped before they are
 * ever reached.
 *
 * Three rules keep it from being annoying, which is the whole risk:
 *
 *  - [LOCKED] songs after the current one are never touched. What is next stays next. Somebody
 *    who looked at the queue thirty seconds ago must not find it rearranged under them.
 *  - Order is preserved inside each group. This is a filter, not a re-sort: a queue that
 *    reshuffled itself continuously would be unreadable.
 *  - It never removes more than [MAX_DROP_SHARE] of the tail at once, and never empties it.
 *    A wrong call costs a few songs, not the queue.
 *
 * No Android in here, so it is tested.
 */
object AdaptiveQueue {

    /** Songs after the current one that are never reconsidered. */
    const val LOCKED = 4

    /** At most this share of the provisional tail may go in one pass. */
    const val MAX_DROP_SHARE = 0.4

    /**
     * The share allowed when the listener has just said something clear.
     *
     * Skipping a song is the strongest opinion anyone offers without pressing anything, and liking
     * one is the strongest they offer by pressing something. Both mean the picture just changed,
     * so the tail should move further than it does when a song merely ended.
     */
    const val STRONG_DROP_SHARE = 0.75

    /** Songs kept untouched after a clear signal. Fewer, because the listener just acted. */
    const val STRONG_LOCKED = 2

    /** Below this fit, a song is considered wrong for what is happening now. */
    const val DROP_BELOW = -0.25

    /** What a pass decided: the tail to keep, in order, and what it took out. */
    data class Plan<T>(val keep: List<T>, val dropped: List<T>) {
        val changed: Boolean get() = dropped.isNotEmpty()
    }

    /**
     * Which of the songs still ahead should stay.
     *
     * [tail] is everything after the locked head, in play order. [context] is what the last few
     * listens sounded like; when it is not known, nothing is dropped, because the honest answer to
     * "what does this listener want right now" after a long gap is that we have no idea.
     */
    fun <T> plan(
        tail: List<T>,
        title: (T) -> String?,
        context: TagFit.Context,
        dropBelow: Double = DROP_BELOW,
        dropShare: Double = MAX_DROP_SHARE,
    ): Plan<T> {
        if (tail.isEmpty() || !context.known) return Plan(tail, emptyList())

        // Only songs that say something about themselves are candidates for removal.
        //
        // TagFit scores an untagged song against a fully treated run at minus one, which is right
        // for ranking: a plain recording really is jarring in the middle of a slowed set. It is
        // wrong for deletion. Most music carries no qualifier at all, so acting on it here would
        // empty the queue of everything except the labelled quarter of the library, which is not
        // a rebalance, it is a purge.
        val scored = tail.map { item ->
            val tags = SongTags.of(title(item))
            item to if (tags.isEmpty()) 0.0 else TagFit.score(tags, context)
        }
        val budget = (tail.size * dropShare).toInt()
        if (budget <= 0) return Plan(tail, emptyList())

        // The worst offenders first, but only up to the budget, and only ones actually below the
        // line. Taking the N worst regardless would drop songs that were perfectly fine whenever
        // the whole tail happened to fit.
        val doomed = scored.asSequence()
            .filter { it.second < dropBelow }
            .sortedBy { it.second }
            .take(budget)
            .map { it.first }
            .toCollection(HashSet())

        if (doomed.isEmpty()) return Plan(tail, emptyList())

        // Order preserved: this removes songs, it does not rearrange them.
        return Plan(tail.filterNot { it in doomed }, tail.filter { it in doomed })
    }

    /**
     * The index the tail starts at, given where playback is.
     *
     * Returns null when there is no tail worth planning, which is the common case: a queue with
     * nothing much left in it is about to be topped up anyway.
     */
    fun tailStart(currentIndex: Int, size: Int, locked: Int = LOCKED): Int? {
        val start = currentIndex + 1 + locked
        return if (start in 0 until size) start else null
    }
}
