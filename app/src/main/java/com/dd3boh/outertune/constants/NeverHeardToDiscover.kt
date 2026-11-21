/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.dd3boh.outertune.engine.ContextChip

/**
 * New songs only and the Never heard choice were taken out of Quick picks on 21 Nov 2026 (his word):
 * songs never heard are the Discover chip's now, by one rule (engine/HeardSet.kt), and both had
 * let liked, saved and downloaded songs through as "New to you".
 *
 * Whoever had either on lands on Discover. It is a chip rather than a choice of what the row leans
 * toward, and the chip is stored as they were, so it is Discover itself they land on, not Auto. The
 * choice goes back to Auto and both stored values go. Run as the settings are first read, and only
 * while one of them is still there, so it happens once; a backup from before brings them back and
 * is moved again as it is read.
 */
object NeverHeardToDiscover : DataMigration<Preferences> {
    /** The New songs only switch, read here and nowhere else. */
    private val newSongsOnlyKey = booleanPreferencesKey("newSongsOnly")

    /** Never heard's name in [QuickPicksLeanKey]. */
    private const val NEVER_HEARD = "NEW"

    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData.contains(newSongsOnlyKey) || currentData[QuickPicksLeanKey] == NEVER_HEARD

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            val wanted = this[newSongsOnlyKey] == true || this[QuickPicksLeanKey] == NEVER_HEARD
            remove(newSongsOnlyKey)
            if (this[QuickPicksLeanKey] == NEVER_HEARD) remove(QuickPicksLeanKey)
            if (wanted) this[ContextChipKey] = ContextChip.DISCOVER
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}
