/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * Where the download folder of Settings > Storage stands, as the last start of the download
 * manager found it (DownloadDirectoryManagerOt.doInit, again after each change of folder).
 * [CANNOT_OPEN] is a folder saved that did not open as a folder: a permission that is gone (a
 * setting brought back by a restored backup, whose folder grant belonged to the old install), a
 * folder deleted, a card taken out. Downloads then stay inside the app, and the app says so on the
 * Storage page and once when a download cannot be moved (11 Nov 2026, found on his phone: 360
 * songs kept inside the app for days without a word).
 */
enum class DownloadFolderState {
    NOT_SET,
    OPEN,
    CANNOT_OPEN;

    companion object {
        fun of(pathSaved: Boolean, opened: Boolean): DownloadFolderState = when {
            !pathSaved -> NOT_SET
            opened -> OPEN
            else -> CANNOT_OPEN
        }
    }
}
