/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.dd3boh.outertune.constants.NotificationsAskedKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    /** The settings file, held here, every write as DataStore makes it: a whole new set of settings. */
    private open class Store(private val fails: Throwable? = null) : DataStore<Preferences> {
        var now: Preferences = emptyPreferences()
        override val data: Flow<Preferences> get() = flowOf(now)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            fails?.let { throw it }
            return transform(now).also { now = it }
        }
    }

    @Test
    fun `the mark is left where it can be written`() = runBlocking {
        val store = Store()
        assertTrue(NotificationAsk.markAsked(store))
        assertEquals(true, store.now[NotificationsAskedKey])
    }

    @Test
    fun `on a full phone the mark is let go and nothing closes the app`() = runBlocking {
        // DataStore's error when its file cannot be written (28 Nov review: uncaught, it ended the app and the music).
        val full = Store(IOException("write failed: ENOSPC (No space left on device)"))
        assertFalse(NotificationAsk.markAsked(full))
        assertNull(full.now[NotificationsAskedKey])
    }

    @Test
    fun `a write stopped with its screen still stops, it is not swallowed`() {
        var thrown: Throwable? = null
        try {
            runBlocking { NotificationAsk.markAsked(Store(CancellationException("the screen went"))) }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
    }
}
