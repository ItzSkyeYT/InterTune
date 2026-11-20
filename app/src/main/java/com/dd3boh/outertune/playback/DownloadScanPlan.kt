/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/** What a look at one song's own file found (DownloadDirectoryManagerOt.lookAgain). */
enum class FolderFileCheck {
    /** The file is there. */
    THERE,

    /** The folder answers and has no such file, or the file was never in a download folder of today's. */
    GONE,

    /** It could not be told: the folder did not answer, or the question failed. */
    UNKNOWN,
}

/**
 * What a full scan of the download folders writes to the database, decided before anything is
 * written, and written in one go.
 *
 * The scan used to clear every song's download first, then record again only the files it could
 * both list and give a path on the phone. A listing that came back short, or a file whose path it
 * could not make, cost each of those songs its download: the next liked catch up fetched every one
 * of them again, and the move after it put a second copy of each in the folder, "Title [id].mka (1)"
 * (dvd, 20 Nov 2026: all 397 of his liked songs).
 *
 * Now a song's download only ends when its file is known to be gone. [recorded] is every song the
 * database has as downloaded, with its file's path (null for one kept inside the app); [listed] the
 * files the listing found, by song, with their path on the phone (null when the file has none, as
 * on another app's storage); [inside] the songs media3 holds whole inside the app; [spared] the
 * songs under a folder that could not be listed at all; [checked] what a look at each other
 * recorded song's own file found. A song with no answer in [checked] has no file to look at, and
 * goes, as one inside the app that media3 no longer has.
 */
data class DownloadScanPlan(
    /** Songs to record as downloaded from their file in a folder: its path on the phone, or null when it has none. */
    val register: Map<String, String?>,
    /** Songs whose download is over. */
    val remove: Set<String>,
) {
    companion object {
        fun of(
            recorded: Map<String, String?>,
            listed: Map<String, String?>,
            inside: Set<String>,
            spared: Set<String>,
            checked: Map<String, FolderFileCheck>,
        ): DownloadScanPlan {
            // A song recorded with this very file stays as it is, its date with it. One with no path
            // to give keeps what it has; recorded only now, it is recorded without one.
            val register = listed.filter { (id, path) -> id !in recorded || (path != null && recorded[id] != path) }
            val remove = recorded.keys.filterTo(mutableSetOf()) { id ->
                id !in listed && id !in inside && id !in spared && goes(checked[id])
            }
            return DownloadScanPlan(register, remove)
        }

        /** Whether a song not listed goes, from the look at its own file: only when that says gone, or there was none to look at. */
        fun goes(check: FolderFileCheck?): Boolean = (check ?: FolderFileCheck.GONE) == FolderFileCheck.GONE

        /**
         * The songs, out of [recorded] (each with its file's path), whose file is to be looked at one by
         * one: not listed, not inside the app, not under a folder that could not be listed, and with a
         * path to look at.
         */
        fun toLookAt(
            recorded: Map<String, String?>,
            listed: Set<String>,
            inside: Set<String>,
            spared: Set<String>,
        ): Map<String, String> = recorded.entries
            .filter { (id, path) -> path != null && id !in listed && id !in inside && id !in spared }
            .associate { (id, path) -> id to path!! }
    }
}
