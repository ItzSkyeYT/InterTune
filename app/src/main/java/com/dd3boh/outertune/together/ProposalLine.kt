/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.together.TogetherSession.Proposal

/**
 * The songs guests proposed to the phone in control, each waiting for Play it or Not now (22 Nov 2026). One proposal
 * used to be held, and a second guest's took its place unseen; a tap beside its dialog or Back threw it away (dvd, in
 * control in #testers, was typing when it came and never read it). Now each waits, the oldest asked first, one per
 * guest: a guest's newer song takes the place of its older one, which it no longer plays.
 */
internal object ProposalLine {
    /** [new] joins [line]: in the place of the same guest's older one, or last. A guest with no key is never merged. */
    fun add(line: List<Proposal>, new: Proposal): List<Proposal> {
        val at = if (new.from.isEmpty()) -1 else line.indexOfFirst { it.from == new.from }
        return if (at < 0) line + new else line.toMutableList().also { it[at] = new }
    }

    /** [line] without [answered], once Play it or Not now was pressed on it. */
    fun without(line: List<Proposal>, answered: Proposal): List<Proposal> = line.filterNot { it == answered }

    /**
     * Where a proposed song starts once the person in control plays it, [waitedMs] after it came: after a quick answer,
     * where the guest is in it by now, so the guest hears no jump coming back; after a long one, from the top. A
     * proposal waits as long as the answer takes now, and by then the guest is often past the song's end, where a seek
     * would skip the song for everyone.
     */
    fun startAt(positionMs: Long, waitedMs: Long): Long =
        if (waitedMs in 0..QUICK_ANSWER_MS) positionMs + waitedMs else 0L

    /** An answer this quick still finds the guest in the song it proposed. */
    const val QUICK_ANSWER_MS = 30_000L
}
