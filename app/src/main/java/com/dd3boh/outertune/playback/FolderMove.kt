/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/** How one song's move into the download folder ended: see [moveIntoFolder]. */
internal enum class FolderMove { MOVED, NOT_WRITTEN, NO_PATH, NOT_RECORDED, REMOVED }

/**
 * Moves one downloaded song from inside the app into the download folder, in the order that never
 * leaves it without a copy that plays: its file written whole ([save] gives the file, or null when
 * none could be written), the song's row pointing to that file ([record], given its path on the
 * phone from [pathOf]), and only then its copy inside the app dropped ([dropCopy]).
 *
 * The copy used to go first, and the song's row learnt the file only from the full scan after the
 * whole batch: until then the song played from the network, or offline not at all, and a Migrate
 * cut short skipped that scan. A step that fails ends the move here and the song keeps its copy in
 * the app, as a file with no path (a folder on another app's storage, which no scan can register
 * either) or a row that could not be written would otherwise leave nothing that plays it.
 *
 * A song whose download was removed meanwhile ([removed], asked once the file is made and again once
 * the row is written) is not moved: the file goes ([unsave]), the row is taken back ([unrecord]) when it
 * was written, and the copy is left to media3, whose removal drops it. A conversion to MP3 takes a
 * minute or more, and a song unliked during it still landed in the folder, recorded as downloaded,
 * while every screen said it was not (28 Nov 2026).
 *
 * Takes plain steps, so the order is tested without a folder, a database or media3.
 */
internal inline fun <F : Any> moveIntoFolder(
    save: () -> F?,
    pathOf: (F) -> String?,
    record: (String) -> Unit,
    dropCopy: () -> Unit,
    removed: () -> Boolean = { false },
    unsave: (F) -> Unit = {},
    unrecord: () -> Unit = {},
): FolderMove {
    val file = save() ?: return FolderMove.NOT_WRITTEN
    if (removed()) {
        unsave(file)
        return FolderMove.REMOVED
    }
    val path = pathOf(file) ?: return FolderMove.NO_PATH
    if (runCatching { record(path) }.isFailure) return FolderMove.NOT_RECORDED
    if (removed()) {
        unsave(file)
        unrecord()
        return FolderMove.REMOVED
    }
    dropCopy()
    return FolderMove.MOVED
}
