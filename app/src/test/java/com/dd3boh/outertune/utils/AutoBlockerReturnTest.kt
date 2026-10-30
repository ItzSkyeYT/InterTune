/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.AutoBlockerBlockedByKey
import com.dd3boh.outertune.constants.AutoBlockerReturnAtKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Samsung's Auto Blocker stops the update, and the person is told to turn it off: when they are
 * back, the install starts again by itself, once; and after the update has gone through, the new
 * build says Auto Blocker can go back on, once. These hold the rules that decide both, and that
 * what they keep is kept in the settings file, since the app can be ended while the person is in
 * Settings, and an update of the app ends it for certain.
 */
class AutoBlockerReturnTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    /** A store on the one file, as the app's settings are. */
    private fun store(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { dir.root.resolve("settings.preferences_pb") }
    }

    @After
    fun close() = scopes.forEach { it.cancel() }

    private fun DataStore<Preferences>.asked(): Long? = runBlocking { data.first()[AutoBlockerReturnAtKey] }

    private val tapped = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun `back in time after being away, the install starts again`() {
        assertEquals(ReturnVerdict.INSTALL_AGAIN, returnVerdict(tapped, tapped + 2 * minute, away = true))
        assertEquals(ReturnVerdict.INSTALL_AGAIN, returnVerdict(tapped, tapped + 29 * minute, away = true))
    }

    @Test
    fun `half an hour is the limit, to the millisecond`() {
        assertEquals(30 * minute, AUTO_BLOCKER_RETURN_MS)
        assertEquals(ReturnVerdict.INSTALL_AGAIN, returnVerdict(tapped, tapped + AUTO_BLOCKER_RETURN_MS, away = true))
        assertEquals(ReturnVerdict.LAPSED, returnVerdict(tapped, tapped + AUTO_BLOCKER_RETURN_MS + 1, away = true))
        assertEquals(ReturnVerdict.LAPSED, returnVerdict(tapped, tapped + 3 * 60 * minute, away = true))
    }

    @Test
    fun `a clock put back since the tap is not trusted, and nothing starts`() {
        assertEquals(ReturnVerdict.LAPSED, returnVerdict(tapped, tapped - 1, away = true))
    }

    @Test
    fun `nothing starts for a tap that was never made`() {
        assertEquals(ReturnVerdict.NOTHING_OWED, returnVerdict(null, tapped, away = true))
        assertEquals(ReturnVerdict.NOTHING_OWED, returnVerdict(null, tapped, away = false))
    }

    @Test
    fun `a resume before the person has left is not a return, and the tap waits`() {
        assertEquals(ReturnVerdict.NOT_AWAY_YET, returnVerdict(tapped, tapped + 1, away = false))
        // A tap that has lapsed is forgotten whether or not the person left.
        assertEquals(ReturnVerdict.LAPSED, returnVerdict(tapped, tapped + AUTO_BLOCKER_RETURN_MS + 1, away = false))
    }

    @Test
    fun `the install starts once for a tap, and the next resume finds nothing`() = runBlocking {
        val store = store()
        AutoBlockerReturn.ask(store, tapped)
        assertEquals(ReturnVerdict.INSTALL_AGAIN, AutoBlockerReturn.take(store, tapped + minute, away = true))
        assertNull("the tap is forgotten as it is used", store.asked())
        assertEquals(ReturnVerdict.NOTHING_OWED, AutoBlockerReturn.take(store, tapped + 2 * minute, away = true))
    }

    @Test
    fun `two resumes at once cannot both start it`() = runBlocking {
        val store = store()
        AutoBlockerReturn.ask(store, tapped)
        val verdicts = coroutineScope {
            List(8) { async(Dispatchers.IO) { AutoBlockerReturn.take(store, tapped + minute, away = true) } }.awaitAll()
        }
        assertEquals(1, verdicts.count { it == ReturnVerdict.INSTALL_AGAIN })
        assertEquals(7, verdicts.count { it == ReturnVerdict.NOTHING_OWED })
    }

    @Test
    fun `a resume before the person has left keeps the tap for the one after`() = runBlocking {
        val store = store()
        AutoBlockerReturn.ask(store, tapped)
        assertEquals(ReturnVerdict.NOT_AWAY_YET, AutoBlockerReturn.take(store, tapped + 1, away = false))
        assertEquals("kept", tapped, store.asked())
        assertEquals(ReturnVerdict.INSTALL_AGAIN, AutoBlockerReturn.take(store, tapped + minute, away = true))
    }

    @Test
    fun `a tap that lapsed is forgotten without starting anything, and a new tap asks again`() = runBlocking {
        val store = store()
        AutoBlockerReturn.ask(store, tapped)
        assertEquals(ReturnVerdict.LAPSED, AutoBlockerReturn.take(store, tapped + 31 * minute, away = true))
        assertNull(store.asked())
        assertEquals(ReturnVerdict.NOTHING_OWED, AutoBlockerReturn.take(store, tapped + 32 * minute, away = true))

        // Blocked again, a second tap: owed again, once more. No loop of its own.
        AutoBlockerReturn.ask(store, tapped + 40 * minute)
        assertEquals(ReturnVerdict.INSTALL_AGAIN, AutoBlockerReturn.take(store, tapped + 41 * minute, away = true))
        assertEquals(ReturnVerdict.NOTHING_OWED, AutoBlockerReturn.take(store, tapped + 42 * minute, away = true))
    }

    @Test
    fun `the tap outlives the app being ended in Settings`() = runBlocking {
        val before = store()
        AutoBlockerReturn.ask(before, tapped)
        // The process dies: its store goes with it, and the next process opens the same file.
        scopes.forEach { it.cancel() }
        scopes.clear()

        val after = store()
        assertEquals(tapped, after.asked())
        assertEquals(ReturnVerdict.INSTALL_AGAIN, AutoBlockerReturn.take(after, tapped + 10 * minute, away = true))
    }

    @Test
    fun `a process that starts with a tap remembered has been away, and the tap alone does not make the person gone`() {
        val presence = Presence()
        assertTrue("a new process never saw the tap", presence.away)
        presence.asked()
        assertFalse("the tap, with the screen still InterTune's", presence.away)
        presence.left()
        assertTrue("InterTune left the front", presence.away)
        presence.left()
        assertTrue("leaving again changes nothing", presence.away)
        presence.asked()
        assertFalse("a second tap starts the wait over", presence.away)
    }

    @Test
    fun `a build owes a word about a block that an older build saw, and about no other`() {
        // Blocked on 97 (0.12.5), updated to 98.
        assertTrue(reminderOwed(blockedBy = 97, running = 98))
        assertTrue(reminderOwed(blockedBy = 97, running = 140))
        // Still the build that saw it: the update has not gone through.
        assertFalse(reminderOwed(blockedBy = 97, running = 97))
        // The app was put back to an older build: no update happened.
        assertFalse(reminderOwed(blockedBy = 98, running = 97))
        // Never blocked, or a note that is not a version.
        assertFalse(reminderOwed(blockedBy = null, running = 98))
        assertFalse(reminderOwed(blockedBy = 0, running = 98))
        assertFalse(reminderOwed(blockedBy = -1, running = 98))
    }

    @Test
    fun `the note is set when an install is blocked, owed to the next build, and cleared once said`() = runBlocking {
        val store = store()
        assertNull(store.data.first()[AutoBlockerBlockedByKey])

        AutoBlockerReturn.noteBlock(store, versionCode = 97)
        assertEquals(97, store.data.first()[AutoBlockerBlockedByKey])
        assertFalse("not by the build that saw it", reminderOwed(store.data.first()[AutoBlockerBlockedByKey], running = 97))
        assertTrue("by the build that replaces it", reminderOwed(store.data.first()[AutoBlockerBlockedByKey], running = 98))

        AutoBlockerReturn.clearNote(store)
        assertNull(store.data.first()[AutoBlockerBlockedByKey])
        assertFalse("said once", reminderOwed(store.data.first()[AutoBlockerBlockedByKey], running = 98))
    }

    @Test
    fun `blocked again by the build that replaced it, the note is that build's`() = runBlocking {
        val store = store()
        AutoBlockerReturn.noteBlock(store, versionCode = 97)
        AutoBlockerReturn.noteBlock(store, versionCode = 98)
        assertEquals(98, store.data.first()[AutoBlockerBlockedByKey])
        assertFalse(reminderOwed(store.data.first()[AutoBlockerBlockedByKey], running = 98))
        assertTrue(reminderOwed(store.data.first()[AutoBlockerBlockedByKey], running = 99))
    }

    @Test
    fun `the tap and the note are kept apart, so using one leaves the other`() = runBlocking {
        val store = store()
        AutoBlockerReturn.noteBlock(store, versionCode = 97)
        AutoBlockerReturn.ask(store, tapped)
        assertEquals(ReturnVerdict.INSTALL_AGAIN, AutoBlockerReturn.take(store, tapped + minute, away = true))
        assertEquals("the note waits for the update", 97, store.data.first()[AutoBlockerBlockedByKey])
        AutoBlockerReturn.clearNote(store)
        AutoBlockerReturn.ask(store, tapped + 5 * minute)
        assertEquals(tapped + 5 * minute, store.asked())
    }
}
