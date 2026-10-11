/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.engine.SimilarSources

/** The questions of setup's last page, in the order they are put. */
enum class SetupQuestion {
    /** Whether to look for a new version. */
    UPDATES,

    /**
     * News and the occasional question on Home, asked as one: a word from InterTune now and then.
     * They are two settings and stay two in Settings. Here a yes is a yes to both, and a no a no
     * to both.
     */
    WORD,

    /** Whether this install counts itself once a day. */
    COUNT,

    /** Similar songs from Last.fm too. Not put where it cannot be asked (lastFmQuestionAskable). */
    LASTFM,
}

/**
 * Setup's last page as it stands: which questions have an answer, and which one is in the card.
 *
 * The page this is for was a list of five switches, and a list of five switches reads as
 * something to get past. It puts one question at a time now, with a yes and a no under it. Each
 * answer goes above the card as a short row, and a tap on a row puts its question back in the
 * card, to be answered again.
 *
 * A switch has no unanswered position. A question has, and the page shows it: a question without
 * an answer has no row. So this holds three states for each, yes, no and none yet, where
 * SetupChoices holds two. Nothing is stored while the questions are answered. The tick stores
 * what [choices] makes of them, whenever it is tapped.
 *
 * @param asked the questions this build puts, in order
 * @param answers the answers given so far. A question that is not in here has none yet
 * @param open the question in the card. None once every question has its answer and none of them
 *   is being changed: the page then says that is all
 */
data class SetupQuestions(
    val asked: List<SetupQuestion>,
    val answers: Map<SetupQuestion, Boolean>,
    val open: SetupQuestion?,
) {
    /**
     * [question] answered. The card goes to the first question that still has no answer: the
     * next one down, or the one that was waiting while an earlier answer was changed.
     */
    fun answer(question: SetupQuestion, yes: Boolean): SetupQuestions {
        if (question !in asked) return this
        val given = answers + (question to yes)
        return copy(answers = given, open = asked.firstOrNull { it !in given })
    }

    /**
     * An answered [question] back in the card, from a tap on its row. Its answer stands until
     * another is given: looking at a question again changes nothing.
     */
    fun reopen(question: SetupQuestion): SetupQuestions =
        if (question in answers) copy(open = question) else this

    /**
     * What the tick stores.
     *
     * A question nobody answered is a no, which is what a switch nobody touched stored: off, and
     * updates by SetupChoices.UPDATES_DEFAULT, which is off too. Last.fm's as well, where it is
     * put: left unanswered it would be owed (catchUpOwed), and the catch-up screen, a list of
     * switches, would open as setup closes. A build that cannot ask it stores nothing for it.
     */
    fun choices(): SetupChoices {
        val word = answers[SetupQuestion.WORD] ?: false
        return SetupChoices(
            updates = answers[SetupQuestion.UPDATES] ?: SetupChoices.UPDATES_DEFAULT,
            questions = word,
            news = word,
            count = answers[SetupQuestion.COUNT] ?: false,
            lastFm = if (SetupQuestion.LASTFM in asked) answers[SetupQuestion.LASTFM] ?: false else null,
        )
    }

    /**
     * What a rotation keeps: a character for each question put, 1 for a yes, 0 for a no and a
     * dash for none yet, and then the place of the question in the card, or a dash for none.
     */
    fun saved(): String = asked.joinToString("") {
        when (answers[it]) {
            true -> "1"
            false -> "0"
            null -> "-"
        }
    } + (open?.let { asked.indexOf(it).toString() } ?: "-")

    companion object {
        /** The questions a build puts: Last.fm's only where it can be asked. */
        private fun asked(lastFmAskable: Boolean) =
            SetupQuestion.entries.filter { it != SetupQuestion.LASTFM || lastFmAskable }

        /**
         * The page as it opens. On a new install nothing is answered, and the first question is
         * in the card. Opened again, from Developer or on its last page after an update, it has
         * a row for every answer given before, and the first question that never had one in the
         * card. With all of them answered that is none, and the page says that is all.
         *
         * News and questions have an answer here only where both were given one, and the same
         * one. Set apart in Settings, or with one of the two never asked, they are a question
         * without an answer: it is put again, and left alone it is a no to both, like any other.
         */
        fun from(prefs: Preferences, lastFmAskable: Boolean): SetupQuestions {
            val stored = SetupChoices.from(prefs, lastFmAskable)
            val answers = buildMap {
                if (prefs[UpdateCheckEnabledKey] != null) put(SetupQuestion.UPDATES, stored.updates)
                if (prefs[PollsEnabledKey] != null && prefs[AnnouncementsEnabledKey] != null && stored.questions == stored.news) {
                    put(SetupQuestion.WORD, stored.questions)
                }
                if (prefs[UsageCountEnabledKey] != null) put(SetupQuestion.COUNT, stored.count)
                // A choice made through the setting or the old switch is an answer too.
                if (stored.lastFm != null && SimilarSources.asked(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey])) {
                    put(SetupQuestion.LASTFM, stored.lastFm)
                }
            }
            val asked = asked(lastFmAskable)
            return SetupQuestions(asked, answers, open = asked.firstOrNull { it !in answers })
        }

        /**
         * The reverse of [saved], or null for anything that is not a page of this build's
         * questions: the page then starts from what is stored.
         */
        fun ofSaved(saved: String, lastFmAskable: Boolean): SetupQuestions? {
            val asked = asked(lastFmAskable)
            if (saved.length != asked.size + 1 || saved.dropLast(1).any { it !in "01-" }) return null
            val answers = asked.zip(saved.toList())
                .filter { (_, mark) -> mark != '-' }
                .associate { (question, mark) -> question to (mark == '1') }
            val open = when (val place = saved.last()) {
                // No question in the card is a page with every answer given, and nothing else.
                '-' -> if (asked.all { it in answers }) null else return null
                else -> asked.getOrNull(place.digitToIntOrNull() ?: return null) ?: return null
            }
            return SetupQuestions(asked, answers, open)
        }
    }
}
