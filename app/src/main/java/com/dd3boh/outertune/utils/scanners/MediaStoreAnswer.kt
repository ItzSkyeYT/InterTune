/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

/**
 * What the MediaStore scanner may take from MediaStore's answer. No Android in here, so it is
 * tested.
 *
 * The scanner took an empty answer for a folder to mean the folder was empty, and disabled every
 * song it had scanned from there. MediaStore answers nothing for a folder that has a .nomedia file
 * in it, or that Android has not indexed yet (a card it has not looked at), so a scan could take
 * every local song out of the library at once (15 Nov 2026).
 */
object MediaStoreAnswer {

    /**
     * The scan folders whose songs a sync leaves as they are, because MediaStore's answer does not
     * tell what is in them.
     *
     * A folder it gave no song for, where the library has songs, is one it cannot speak for: the
     * same empty answer comes from a folder emptied by hand, but also from one Android does not
     * list. Disabled for that, a song would stay out of the library until a scan could see its
     * folder again. Songs under an excluded folder are meant to go, so they are no reason to
     * keep a folder.
     *
     * A null folder is one whose path is not known: MediaStore was never asked about it, and every
     * song stays, as it does for the other scanners (ScanMerge.coveredByUnlistedRoot).
     *
     * @param folders the scan folders as absolute paths, null for one whose path is not known
     * @param answered the path of every song MediaStore gave, the excluded ones already left out
     * @param inLibrary the path of every local song the library has now
     * @param excluded the excluded folders as absolute paths
     * @return the folders to keep, as they were given, in the same order
     */
    fun foldersToKeep(
        folders: List<String?>,
        answered: List<String>,
        inLibrary: List<String>,
        excluded: List<String>,
    ): List<String?> = folders.filter { folder ->
        folder == null || (
                answered.none { under(it, folder) } &&
                        inLibrary.any { path -> under(path, folder) && excluded.none { under(path, it) } }
                )
    }

    /** Whether [path] is somewhere inside [folder]: a name that only starts the same is another folder. */
    private fun under(path: String, folder: String): Boolean = path.startsWith(folder.trimEnd('/') + "/")
}
