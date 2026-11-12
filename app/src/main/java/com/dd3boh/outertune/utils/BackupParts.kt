/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

/**
 * What one backup file holds, picked on the backup sheet. The account sign-ins are off unless asked
 * for: a backup file sits in a folder or a chat, and anyone who has it could use the accounts in it
 * (his yes, 4 Nov 2026). The scheduled backup always takes the defaults.
 */
data class BackupParts(
    val library: Boolean = true,
    val settings: Boolean = true,
    /** The photos chosen as playlist covers. They belong to the library's playlists, so they go with it. */
    val photos: Boolean = true,
    /** The sign-ins live in the settings, so they go only with them. */
    val signIns: Boolean = false,
) {
    /** Whether the file holds anything a restore can use. */
    val anything: Boolean get() = library || settings
}

/** The settings that sign in to an account: YouTube Music's and Last.fm's. */
object SignIns {
    val KEYS: List<Preferences.Key<String>> = listOf(
        InnerTubeCookieKey, DataSyncIdKey, AccountNameKey, AccountEmailKey, AccountChannelHandleKey,
        LastFmSessionKey, LastFmUsernameKey,
    )

    fun without(prefs: Preferences): Preferences =
        prefs.toMutablePreferences().apply { KEYS.forEach { remove(it) } }.toPreferences()

    /**
     * A restored backup's settings, with the sign-ins already on this phone kept when the backup has
     * none of its own: a restore must not sign anybody out. A backup that holds sign-ins brings them.
     */
    fun keptOver(restored: Preferences, current: Preferences): Preferences {
        if (KEYS.any { restored[it] != null }) return restored
        return restored.toMutablePreferences().apply { KEYS.forEach { key -> current[key]?.let { this[key] = it } } }.toPreferences()
    }
}

/**
 * A settings file's bytes, read and written by DataStore itself through a file of its own, so that
 * a backup can hold the settings without their sign-ins and a restore can put the phone's back.
 */
object SettingsFile {
    suspend fun encode(prefs: Preferences, dir: File): ByteArray = withStore(dir) { store, file ->
        store.edit { it.clear(); it += prefs }
        file.readBytes()
    }

    suspend fun decode(bytes: ByteArray, dir: File): Preferences = withStore(dir, bytes) { store, _ ->
        store.data.first()
    }

    // A new file every time: DataStore refuses two stores on one file in a process.
    private suspend fun <T> withStore(dir: File, bytes: ByteArray? = null, block: suspend (androidx.datastore.core.DataStore<Preferences>, File) -> T): T {
        val file = File(dir.apply { mkdirs() }, "settings-copy-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            if (bytes != null) file.writeBytes(bytes)
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            return block(store, file)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
