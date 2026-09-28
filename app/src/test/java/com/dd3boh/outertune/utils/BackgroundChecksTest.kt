/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

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
}
