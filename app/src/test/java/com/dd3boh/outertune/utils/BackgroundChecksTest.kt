/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.work.ExistingPeriodicWorkPolicy.KEEP
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundChecksTest {
    @Test
    fun `a version already notified about is not notified again`() {
        assertFalse(shouldNotifyUpdate(versionCode = 91, lastNotifiedCode = 91, snoozeUntil = 0L, now = 1_000L))
    }

    @Test
    fun `a newer version than the one last notified is notified`() {
        assertTrue(shouldNotifyUpdate(versionCode = 92, lastNotifiedCode = 91, snoozeUntil = 0L, now = 1_000L))
    }

    @Test
    fun `a first run with nothing notified yet still notifies`() {
        assertTrue(shouldNotifyUpdate(versionCode = 91, lastNotifiedCode = -1, snoozeUntil = 0L, now = 1_000L))
    }

    @Test
    fun `remind me later is honoured even for a version never notified before`() {
        assertFalse(shouldNotifyUpdate(versionCode = 91, lastNotifiedCode = -1, snoozeUntil = 2_000L, now = 1_000L))
        assertTrue(shouldNotifyUpdate(versionCode = 91, lastNotifiedCode = -1, snoozeUntil = 2_000L, now = 2_000L))
    }

    @Test
    fun `a poll already notified about is not notified again, and a new one still is`() {
        assertFalse(shouldNotifyPoll(pollId = "p1", lastNotifiedId = "p1"))
        assertTrue(shouldNotifyPoll(pollId = "p2", lastNotifiedId = "p1"))
        assertTrue(shouldNotifyPoll(pollId = "p1", lastNotifiedId = ""))
    }

    // Whether a launch keeps the schedule or replaces it.

    /** The tag WorkManager adds to every request by itself: the worker's class name. */
    private val workerTag = "com.dd3boh.outertune.utils.BackgroundCheckWorker"

    private fun scheduled(hours: Int) = setOf(workerTag, backgroundCheckTag(hours))

    @Test
    fun `the tag is stored with the schedule, so its format stays put`() {
        assertEquals("background_checks_interval_hours=5", backgroundCheckTag(5))
    }

    @Test
    fun `a launch keeps a schedule made for the interval in the setting`() {
        // Every launch with the checks on used to replace it, which ran a check at once.
        for (hours in listOf(1, 2, 5, 10, 24)) {
            assertEquals("every $hours h", KEEP, backgroundCheckPolicy(scheduled(hours), hours))
        }
    }

    @Test
    fun `with nothing scheduled the schedule is enqueued, not replaced`() {
        assertEquals(KEEP, backgroundCheckPolicy(null, 5))
    }

    @Test
    fun `another interval replaces the schedule`() {
        // A new pick in Settings, or a Restore that brought another interval back.
        assertEquals(UPDATE, backgroundCheckPolicy(scheduled(24), 1))
        assertEquals(UPDATE, backgroundCheckPolicy(scheduled(1), 24))
    }

    @Test
    fun `a schedule from before the tag is replaced once, and kept from then on`() {
        assertEquals(UPDATE, backgroundCheckPolicy(setOf(workerTag), 5))
        assertEquals(UPDATE, backgroundCheckPolicy(emptySet(), 5))
        assertEquals(UPDATE, backgroundCheckPolicy(setOf(workerTag, "background_checks_interval_hours=x"), 5))
        assertEquals(KEEP, backgroundCheckPolicy(scheduled(5), 5))
    }

    @Test
    fun `the automatic backup's tag is not taken for this one`() {
        assertEquals(UPDATE, backgroundCheckPolicy(setOf(workerTag, AutoBackupPolicy.intervalTag(5)), 5))
    }
}
