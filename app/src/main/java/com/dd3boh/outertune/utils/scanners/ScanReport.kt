/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

/**
 * What a scan has to say to the person who started it. No Android in here, so it is tested.
 *
 * A scan that found nothing said nothing: "0/0 songs processed" and the Scan button again. Four
 * different things sit behind that, and each has its own way out:
 *  - a folder the scan could not open at all (its access is gone, or the card was swapped): add it
 *    again;
 *  - songs the system scanner had nothing for, in a folder where the library has songs: Android
 *    does not list a folder with a .nomedia file in it, and the songs there are left as they are;
 *  - songs found but refused when the app tried to read them: Android keeps a file out of an app's
 *    reach when it has not indexed it, which is what a .nomedia file in the folder does, and
 *    playing such a file fails the same way (EACCES);
 *  - a folder with no music in it.
 */
object ScanReport {

    /**
     * One scan folder as the scan met it: its name, whether it could be looked in, the songs found
     * in it, and whether the songs the library already has there were left alone because the scan
     * could not tell (MediaStoreAnswer).
     */
    data class Folder(val name: String, val listed: Boolean, val found: Int, val kept: Boolean = false)

    sealed interface Note {
        /** Folders that could not be opened. */
        data class Unreadable(val names: List<String>) : Note

        /** Folders the system scanner had nothing for, whose songs in the library were left as they are. */
        data class Kept(val names: List<String>) : Note

        /** Songs that were found and could not be read. Said without a count: the way out is the same for one. */
        data object Blocked : Note

        /** Every folder opened and none held a song. */
        data class Empty(val names: List<String>) : Note
    }

    /**
     * The one thing to say after a scan, or null when there is nothing to add. A folder that
     * cannot be opened comes first, since adding it again is the fix, then songs left as they are,
     * then songs that were refused, then a scan in which no folder held a song. Folders without
     * songs among others that had some are an ordinary scan and say nothing.
     *
     * @param blocked how many songs were found and could not be read
     */
    fun noteFor(folders: List<Folder>, blocked: Int): Note? {
        val closed = folders.filterNot { it.listed }
        if (closed.isNotEmpty()) return Note.Unreadable(names(closed))
        val kept = folders.filter { it.kept }
        if (kept.isNotEmpty()) return Note.Kept(names(kept))
        if (blocked > 0) return Note.Blocked
        if (folders.isNotEmpty() && folders.all { it.found == 0 }) return Note.Empty(names(folders))
        return null
    }

    /** Each name once: two folders called Music on two cards are one word in the note. */
    private fun names(folders: List<Folder>) = folders.map { it.name }.distinct()
}
