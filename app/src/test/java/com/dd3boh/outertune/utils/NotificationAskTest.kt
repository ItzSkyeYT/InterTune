/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's question about notifications, put once (the whole-app scan of 28 Nov 2026, platform
 * finding 2): from Android 13 nothing asked for them where they are the feature, so the sleep
 * timer's countdown, the downloads' progress and a room's proposals never showed on a fresh install.
 */
class NotificationAskTest {
    private val either = listOf(false, true)

    private fun ask(sdk: Int = 33, granted: Boolean = false, asked: Boolean = false, saidNo: Boolean = false) =
        NotificationAsk.shouldAsk(sdk, granted, asked, saidNo)

    @Test
    fun `on Android 13 and later it asks when notifications are missing and nothing asked before`() {
        for (sdk in listOf(33, 34, 35, 36, 37)) assertTrue("API $sdk", ask(sdk = sdk))
    }

    @Test
    fun `once for every use together, never twice`() {
        assertFalse(ask(asked = true))
    }

    @Test
    fun `never after a no to Android's question, wherever it was asked`() {
        // Recognition and the update check ask too: a no there is a no here.
        assertFalse(ask(saidNo = true))
    }

    @Test
    fun `never when notifications are allowed`() {
        assertFalse(ask(granted = true))
    }

    @Test
    fun `before Android 13 there is nothing to ask`() {
        for (sdk in 24..32) for (granted in either) for (asked in either) for (saidNo in either) {
            assertFalse("API $sdk", ask(sdk, granted, asked, saidNo))
        }
    }

    @Test
    fun `a use while the app is on screen is heard`() = runBlocking {
        val heard = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(2_000) { NotificationAsk.used.first() } }
        NotificationAsk.atUse()
        assertNotNull(heard.await())
    }

    @Test
    fun `a use with no screen to ask on is let go, not kept for the next start`() = runBlocking {
        NotificationAsk.atUse()
        assertNull(withTimeoutOrNull(200) { NotificationAsk.used.first() })
    }
}
