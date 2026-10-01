/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AutoBackupEnabledKey
import com.dd3boh.outertune.constants.AutoBackupFolderKey
import com.dd3boh.outertune.constants.AutoBackupIntervalHoursKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the settings say once a schedule call has been applied, on a real preferences file. Every
 * AutoBackup.schedule call goes through AutoBackup.saveOverrides, one at a time and in order, and
 * schedules or cancels by what it returns.
 *
 * The settings screen saves its own copy of each value fire and forget, which can land well after
 * the calls below. These tests leave it out until they say otherwise, which is the case that went
 * wrong: the process killed while the folder picker was open, so that the picker's result and the
 * launch's own call arrive together, before the screen's save.
 */
class AutoBackupScheduleTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { dir.root.resolve("settings.preferences_pb") }
    }

    private val folder = "content://com.android.externalstorage.documents/tree/primary%3AIT-Backups"

    @After
    fun close() = scope.cancel()

    private fun apply(enabled: Boolean? = null, folder: String? = null, hours: Int? = null): Int? =
        runBlocking { AutoBackup.saveOverrides(store, enabled, folder, hours) }

    private fun stored(): Preferences = runBlocking { store.data.first() }

    @Test
    fun `the launch after the picker turned backups on keeps the schedule`() {
        runBlocking { store.edit { it[AutoBackupIntervalHoursKey] = 24 } }

        // The picker's result: switch on, folder chosen.
        assertEquals(24, apply(enabled = true, folder = folder))
        // The launch, with no overrides, before the screen's own save has landed. It used to read
        // the switch as off and cancel what the picker had just scheduled.
        assertEquals(24, apply())

        assertEquals(true, stored()[AutoBackupEnabledKey])
        assertEquals(folder, stored()[AutoBackupFolderKey])
    }

    @Test
    fun `the screen's own save landing late changes nothing`() {
        apply(enabled = true, folder = folder)
        runBlocking {
            store.edit {
                it[AutoBackupFolderKey] = folder
                it[AutoBackupEnabledKey] = true
            }
        }
        assertEquals(AutoBackup.DEFAULT_INTERVAL_HOURS, apply())
    }

    @Test
    fun `a switch turned on and straight off again stays off`() {
        runBlocking { store.edit { it[AutoBackupFolderKey] = folder } }

        assertEquals(AutoBackup.DEFAULT_INTERVAL_HOURS, apply(enabled = true))
        assertNull(apply(enabled = false))
        assertNull(apply())
        assertEquals(false, stored()[AutoBackupEnabledKey])
    }

    @Test
    fun `an interval picked is the one the next launch schedules`() {
        apply(enabled = true, folder = folder)

        assertEquals(6, apply(hours = 6))
        assertEquals(6, apply())
        assertEquals(6, stored()[AutoBackupIntervalHoursKey])
    }

    @Test
    fun `an interval picked while off is kept for when they are turned on`() {
        assertNull(apply(hours = 720))
        assertEquals(720, apply(enabled = true, folder = folder))
    }

    @Test
    fun `a call with no overrides saves nothing`() {
        assertNull(apply())
        assertEquals(emptyMap<Preferences.Key<*>, Any>(), stored().asMap())
    }
}
