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
 * The answers of first-run setup as the short setup stores them: five settings, from the
 * questions of its last page (SetupQuestions), and one Done.
 *
 * The old wizard's cards (OptInCards.kt) write an answer the moment a button is tapped, and a card
 * nobody tapped leaves its preference unset. That is how the app tells "never asked" from "said
 * no", and what the catch-up screen reads to ask again (catchUpOwed). Here nothing is written
 * while the questions are answered: Done writes all of them in one edit, and that is the answer.
 * Setup left any other way, by restoring a backup from its first page, writes none of them, and
 * whatever the backup never answered is still asked.
 *
 * The values are the ones the cards write, so an answer means what it has always meant.
 *
 * @param lastFm null where the question is not put, in a build with no Last.fm key
 *   (lastFmQuestionAskable), and where it was put and not answered. Either way it stays
 *   unanswered.
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
            // A yes is Both for somebody who was never asked, and whatever was stored for somebody
            // who had chosen Last.fm alone: a yes cannot tell the two apart, see
            // similarSourceForSwitch.
            val restoreTo = nextRestoreTo(SimilarSource.BOTH, prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey])
            prefs[SimilarSourceKey] = similarSourceForSwitch(lastFm, restoreTo).name
        }
    }

    companion object {
        /**
         * What "Hear about new versions?" is taken as when Done is tapped with no answer to it.
         * Every other question left without one is a no, because a yes to each of them sends
         * something or shows something nobody asked for. This one is the open question: a no is
         * the same rule, but a copy that did not come from F-Droid has no other way to hear of a
         * new version. Whoever sets it to true should decide whether that holds for a copy from
         * F-Droid too, which F-Droid keeps up to date by itself.
         */
        const val UPDATES_DEFAULT = false

        /**
         * The five as they are stored: an answer given before, when setup is run again from
         * Developer or opens on its last page after an update, and otherwise off. Which of them
         * were ever answered is not told here: SetupQuestions.from reads that.
         */
        fun from(prefs: Preferences, lastFmAskable: Boolean) = SetupChoices(
            updates = prefs[UpdateCheckEnabledKey] ?: UPDATES_DEFAULT,
            questions = prefs[PollsEnabledKey] ?: false,
            news = prefs[AnnouncementsEnabledKey] ?: false,
            count = prefs[UsageCountEnabledKey] ?: false,
            lastFm = if (!lastFmAskable) null
            else SimilarSources.stored(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]) != SimilarSource.YOUTUBE,
        )
    }
}
