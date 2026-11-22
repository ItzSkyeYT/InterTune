/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The developer key ([DevBadge]) on a phone of its owner's, pasted in by him under Settings > Advanced > Developer
 * settings > Developer key. Kept in a small file of its own, never in the settings: the app's own backups carry the
 * settings file, and a backup file goes to folders and chats. This one stays out of those, out of the cloud backup and
 * out of a move to a new phone (backup_rules.xml, data_extraction_rules.xml). Never logged and never shown: the row
 * only says whether a key is set. Only the developer key itself is kept; anything else pasted is refused.
 */
object DevKey {
    /** The file's name, as the backup rules leave it out: files/datastore/dev_key.preferences_pb. */
    const val FILE = "dev_key"

    private val Context.devKeyFile: DataStore<Preferences> by preferencesDataStore(name = FILE)
    private val KEY = stringPreferencesKey("key")

    /** The developer key kept on this phone, as it changes, null while none is. */
    fun flow(context: Context): Flow<ByteArray?> = context.applicationContext.devKeyFile.data
        .map { DevBadge.keyOf(it[KEY]) }
        .distinctUntilChanged { a, b -> a contentEquals b }

    /** Whether the developer key is kept on this phone, as it changes. */
    fun isSet(context: Context): Flow<Boolean> = flow(context).map { it != null }.distinctUntilChanged()

    /** Keeps [text] when it is the developer key, and says whether it was. Anything else is not kept. */
    suspend fun put(context: Context, text: String): Boolean {
        val key = DevBadge.keyOf(text) ?: return false
        context.applicationContext.devKeyFile.edit { it[KEY] = key.toHex() }
        return true
    }

    suspend fun clear(context: Context) {
        context.applicationContext.devKeyFile.edit { it.remove(KEY) }
    }
}
