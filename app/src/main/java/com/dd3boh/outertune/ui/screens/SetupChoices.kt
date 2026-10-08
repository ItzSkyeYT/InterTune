/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.engine.SimilarSources

/**
 * The five questions of first-run setup as the short setup puts them: five switches on one page,
 * and one Done.
 *
 * The old wizard's cards (OptInCards.kt) write an answer the moment a button is tapped, and a card
 * nobody tapped leaves its preference unset. That is how the app tells "never asked" from "said
 * no", and what the catch-up screen reads to ask again (catchUpOwed). A switch has no unanswered
 * position, so here nothing is written while the switches are moved: Done writes all of them in
 * one edit, each as its switch stands, and that is the answer. Setup left any other way, by
 * restoring a backup from its first page, writes none of them, and whatever the backup never
 * answered is still asked.
 *
 * The values are the ones the cards write, so an answer means what it has always meant.
 *
 * @param lastFm null where the question is not put, in a build with no Last.fm key
 *   (lastFmQuestionAskable). A question that was never shown stays unanswered.
 */
data class SetupChoices(
    val updates: Boolean,
    val questions: Boolean,
    val news: Boolean,
    val count: Boolean,
    val lastFm: Boolean?,
) {
    /** What Done writes. */
    fun store(prefs: MutablePreferences) {
        prefs[UpdateCheckEnabledKey] = updates
        prefs[PollsEnabledKey] = questions
        prefs[AnnouncementsEnabledKey] = news
        prefs[UsageCountEnabledKey] = count
        if (lastFm != null) {
            // On is Both for somebody who was never asked, and whatever was stored for somebody
            // who had chosen Last.fm alone: the switch cannot tell the two apart, see
            // similarSourceForSwitch.
            val restoreTo = nextRestoreTo(SimilarSource.BOTH, prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey])
            prefs[SimilarSourceKey] = similarSourceForSwitch(lastFm, restoreTo).name
        }
    }

    /** The five positions in a line, with a dash for a switch that is not there: what a rotation keeps. */
    fun positions(): String = listOf(updates, questions, news, count, lastFm).joinToString("") {
        when (it) {
            true -> "1"
            false -> "0"
            null -> "-"
        }
    }

    companion object {
        /**
         * Where "Tell me about updates" stands on an install that was never asked. Every other
         * switch starts off, because each of them sends something or shows something nobody
         * asked for. This one is the open question: off is the same rule, but a copy that did
         * not come from F-Droid has no other way to hear of a new version. Whoever sets it to
         * true should decide whether that holds for a copy from F-Droid too, which F-Droid keeps
         * up to date by itself.
         */
        const val UPDATES_DEFAULT = false

        /**
         * Where the switches stand as the page opens: on an answer given before, when setup is
         * run again from Developer or opens on its last page after an update, and otherwise off.
         */
        fun from(prefs: Preferences, lastFmAskable: Boolean) = SetupChoices(
            updates = prefs[UpdateCheckEnabledKey] ?: UPDATES_DEFAULT,
            questions = prefs[PollsEnabledKey] ?: false,
            news = prefs[AnnouncementsEnabledKey] ?: false,
            count = prefs[UsageCountEnabledKey] ?: false,
            lastFm = if (!lastFmAskable) null
            else SimilarSources.stored(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]) != SimilarSource.YOUTUBE,
        )

        /** The reverse of [positions], or null for anything that is not five of its characters. */
        fun ofPositions(saved: String): SetupChoices? {
            if (saved.length != 5 || saved.any { it !in "01-" } || '-' in saved.take(4)) return null
            return SetupChoices(saved[0] == '1', saved[1] == '1', saved[2] == '1', saved[3] == '1', (saved[4] == '1').takeIf { saved[4] != '-' })
        }
    }
}
