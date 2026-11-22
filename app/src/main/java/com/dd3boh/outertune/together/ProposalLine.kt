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
     * What a yes plays: the guest's song alone, or with the songs after it in the guest's queue when the one in control
     * chose them ([withQueue]). Every proposal carries that queue, up to TogetherMessage.QUEUE_SONGS, and the room was
     * handed all of it unasked (dvd's 29 songs in #testers, 22 Nov 2026).
     */
    fun toPlay(proposal: Proposal, withQueue: Boolean): List<TogetherMessage.Song> =
        if (withQueue) proposal.songs else proposal.songs.take(1)

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

    /** What the proposal notification does as the line changes: see [noticeStep]. */
    enum class NoticeStep { HIDE, KEEP, SHOW }

    /**
     * The notification follows the line ([first] waiting while this phone is in control, null otherwise): it shows that
     * one while InterTune is not in front, stays while it is still the one asked, and goes once it is answered anywhere or
     * control moves. In front, the card asks, and the notification of an answered one goes.
     */
    fun noticeStep(first: Proposal?, showing: Proposal?, inFront: Boolean): NoticeStep = when {
        first == null -> NoticeStep.HIDE
        first == showing -> NoticeStep.KEEP
        inFront -> NoticeStep.HIDE
        else -> NoticeStep.SHOW
    }
}
