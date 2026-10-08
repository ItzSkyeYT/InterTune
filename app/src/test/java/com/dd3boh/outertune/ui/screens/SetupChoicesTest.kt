/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.AutoInstallUpdatesKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.engine.SimilarSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last page of the short setup: five switches and one Done.
 *
 * The app tells "never asked" from "said no" by whether an answer is stored at all, and the
 * catch-up screen asks whatever is missing. So Done has to store an answer for every switch that
 * was on the page, the one its switch shows, and nothing for a question that was not put.
 */
class SetupChoicesTest {

    private val positions = listOf(false, true)

    /** Whether the catch-up screen would open over this store, in a build that can ask Last.fm's question. */
    private fun owed(prefs: MutablePreferences, lastFmAskable: Boolean = true) = catchUpOwed(
        updates = prefs[UpdateCheckEnabledKey],
        questions = prefs[PollsEnabledKey],
        news = prefs[AnnouncementsEnabledKey],
        count = prefs[UsageCountEnabledKey],
        lastFmOwed = lastFmAskable && !SimilarSources.asked(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]),
    )

    @Test
    fun `Done stores every switch as it stands, in all thirty-two positions`() {
        for (updates in positions) for (questions in positions) for (news in positions)
            for (count in positions) for (lastFm in positions) {
                val prefs = mutablePreferencesOf()
                SetupChoices(updates, questions, news, count, lastFm).store(prefs)
                val at = "updates $updates, questions $questions, news $news, count $count, Last.fm $lastFm"
                assertEquals(at, updates, prefs[UpdateCheckEnabledKey])
                assertEquals(at, questions, prefs[PollsEnabledKey])
                assertEquals(at, news, prefs[AnnouncementsEnabledKey])
                assertEquals(at, count, prefs[UsageCountEnabledKey])
                // What the card's two buttons write: a yes is Both, a no is YouTube alone.
                assertEquals(at, if (lastFm) SimilarSource.BOTH.name else SimilarSource.YOUTUBE.name, prefs[SimilarSourceKey])
                assertFalse("nothing left to ask after Done: $at", owed(prefs))
            }
    }

    @Test
    fun `Done with nothing touched is five answers, not five questions left open`() {
        val prefs = mutablePreferencesOf()
        SetupChoices.from(prefs, lastFmAskable = true).store(prefs)
        assertEquals(SetupChoices.UPDATES_DEFAULT, prefs[UpdateCheckEnabledKey])
        assertEquals(false, prefs[PollsEnabledKey])
        assertEquals(false, prefs[AnnouncementsEnabledKey])
        assertEquals(false, prefs[UsageCountEnabledKey])
        assertEquals(SimilarSource.YOUTUBE.name, prefs[SimilarSourceKey])
        assertFalse(owed(prefs))
    }

    @Test
    fun `every switch starts off on an install that was never asked, updates by its constant`() {
        val choices = SetupChoices.from(mutablePreferencesOf(), lastFmAskable = true)
        assertEquals(SetupChoices(SetupChoices.UPDATES_DEFAULT, false, false, false, false), choices)
    }

    @Test
    fun `a question that is not put is not answered`() {
        // A build with no Last.fm key shows four switches.
        val shown = SetupChoices.from(mutablePreferencesOf(), lastFmAskable = false)
        assertNull(shown.lastFm)
        val prefs = mutablePreferencesOf()
        shown.store(prefs)
        assertNull(prefs[SimilarSourceKey])
        assertNull(prefs[SimilarFromLastFmKey])
        // So a later build that has the key still owes the question, and asks it.
        assertFalse(owed(prefs, lastFmAskable = false))
        assertTrue(owed(prefs, lastFmAskable = true))
    }

    @Test
    fun `Done stores the five answers and nothing else`() {
        val prefs = mutablePreferencesOf()
        SetupChoices(updates = true, questions = true, news = true, count = true, lastFm = true).store(prefs)
        assertEquals(
            setOf(UpdateCheckEnabledKey, PollsEnabledKey, AnnouncementsEnabledKey, UsageCountEnabledKey, SimilarSourceKey),
            prefs.asMap().keys,
        )
        // Fetching updates by itself was a second switch under a yes on the old card. It is not
        // asked here, so it stays as it was: off.
        assertNull(prefs[AutoInstallUpdatesKey])
    }

    @Test
    fun `setup run again shows the answers given before and Done leaves them as they were`() {
        for (source in SimilarSource.entries) {
            val prefs = mutablePreferencesOf(
                UpdateCheckEnabledKey to true,
                PollsEnabledKey to false,
                AnnouncementsEnabledKey to true,
                UsageCountEnabledKey to true,
                SimilarSourceKey to source.name,
            )
            val before = prefs.asMap().toMap()

            val choices = SetupChoices.from(prefs, lastFmAskable = true)
            assertEquals(SetupChoices(true, false, true, true, lastFm = source != SimilarSource.YOUTUBE), choices)

            // Last.fm alone must not come back as Both because the switch can only say on.
            choices.store(prefs)
            assertEquals(before, prefs.asMap())
        }
    }

    @Test
    fun `the old Last-fm switch counts as an answer and keeps its meaning through Done`() {
        val prefs = mutablePreferencesOf(SimilarFromLastFmKey to true)
        val choices = SetupChoices.from(prefs, lastFmAskable = true)
        assertEquals(true, choices.lastFm)
        choices.store(prefs)
        assertEquals(SimilarSource.LASTFM, SimilarSources.stored(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]))
    }

    @Test
    fun `the switches come back from a rotation as they stood`() {
        for (updates in positions) for (questions in positions) for (news in positions)
            for (count in positions) for (lastFm in listOf(false, true, null)) {
                val choices = SetupChoices(updates, questions, news, count, lastFm)
                assertEquals(choices, SetupChoices.ofPositions(choices.positions()))
            }
        // Anything else is not a set of switches, and the page starts from what is stored.
        for (saved in listOf("", "0101", "010101", "0-010", "01x10")) assertNull(saved, SetupChoices.ofPositions(saved))
    }

    @Test
    fun `a restored or updated install is still asked what it never answered`() {
        // A backup from before the count and the Last.fm question existed, restored from setup's
        // first page: setup is marked done by the backup, and Done was never tapped here.
        val restored = mutablePreferencesOf(UpdateCheckEnabledKey to true, PollsEnabledKey to false, AnnouncementsEnabledKey to false)
        assertTrue(owed(restored))
        // The page would open on what is stored, with the rest off, and writes nothing by opening.
        val before = restored.asMap().toMap()
        SetupChoices.from(restored, lastFmAskable = true)
        assertEquals(before, restored.asMap())

        // Each question on its own is enough to be asked.
        val switches = listOf(UpdateCheckEnabledKey, PollsEnabledKey, AnnouncementsEnabledKey, UsageCountEnabledKey)
        for (missing in switches) {
            val prefs = mutablePreferencesOf(SimilarSourceKey to SimilarSource.YOUTUBE.name)
            switches.filter { it != missing }.forEach { prefs[it] = false }
            assertTrue("never asked: ${missing.name}", owed(prefs))
        }
        val lastFmNeverAsked = mutablePreferencesOf()
        switches.forEach { lastFmNeverAsked[it] = false }
        assertTrue(owed(lastFmNeverAsked))
        assertFalse(owed(lastFmNeverAsked, lastFmAskable = false))
    }

    @Test
    fun `news is not owed by somebody who said yes to questions`() {
        // The checker writes their yes to news down at the next launch (PollChecker.adoptNewsChoice).
        val prefs = mutablePreferencesOf(
            UpdateCheckEnabledKey to false, PollsEnabledKey to true, UsageCountEnabledKey to false,
            SimilarSourceKey to SimilarSource.YOUTUBE.name,
        )
        assertFalse(owed(prefs))
        prefs[PollsEnabledKey] = false
        assertTrue(owed(prefs))
    }
}
