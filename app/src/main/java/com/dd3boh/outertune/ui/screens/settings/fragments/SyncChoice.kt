/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import com.dd3boh.outertune.constants.SyncMode

/**
 * Sync mode as one row with Off (Ti-Die's review, his answer of 19 Nov 2026), over the two settings it
 * always was: whether the account syncs by itself (YtmSyncKey) and whether changes go back to it
 * (YtmSyncModeKey). Off is the first one off, with the mode left as it was: likes and playlist edits go
 * where they went before, and the setup's switch and everything that reads either key are unchanged.
 */
enum class SyncChoice {
    OFF, RO, RW;

    companion object {
        fun of(syncs: Boolean, mode: SyncMode): SyncChoice = when {
            !syncs -> OFF
            mode == SyncMode.RO -> RO
            else -> RW
        }

        fun apply(choice: SyncChoice, setSyncs: (Boolean) -> Unit, setMode: (SyncMode) -> Unit) {
            when (choice) {
                OFF -> setSyncs(false)
                RO -> { setMode(SyncMode.RO); setSyncs(true) }
                RW -> { setMode(SyncMode.RW); setSyncs(true) }
            }
        }
    }
}
