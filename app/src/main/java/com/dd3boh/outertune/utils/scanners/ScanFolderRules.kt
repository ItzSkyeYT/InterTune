/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

/**
 * Which folders cannot be scan folders, and why: a scan folder may not be, hold or sit inside a
 * download folder, nor an extra import folder (every song in one would be a local song as well as
 * a download). No Android in here, so it is tested.
 *
 * Issue 122: Music was refused with "This folder holds your download folder" and the download
 * folder was nowhere near it. The folder named can be an extra import folder, which the text called
 * a download folder as well, and the dialog gave no way to tell which setting it was.
 */
object ScanFolderRules {

    /** The setting a folder in the way was chosen in. */
    enum class Role { DOWNLOAD, IMPORT }

    /**
     * How a scan folder is in the way: it is, or holds, the folder in the way (the same folder
     * counts as holding it), or it lies inside it.
     */
    enum class Way { HOLDS, INSIDE }

    /** The folder in the way (its tree address), the setting it comes from, and how. */
    data class Conflict(val folder: String, val role: Role, val way: Way)

    /**
     * Why [candidate] cannot be a scan folder, or null when it can. A folder that holds one of the
     * others is named before one that lies inside another, and the download folders before the
     * extra import folders.
     */
    fun conflictOf(candidate: String, downloadFolders: List<String>, importFolders: List<String>): Conflict? {
        val others = downloadFolders.map { it to Role.DOWNLOAD } + importFolders.map { it to Role.IMPORT }
        others.firstOrNull { (folder, _) -> FolderNesting.isSameOrInside(folder, candidate) }
            ?.let { (folder, role) -> return Conflict(folder, role, Way.HOLDS) }
        others.firstOrNull { (folder, _) -> FolderNesting.isSameOrInside(candidate, folder) }
            ?.let { (folder, role) -> return Conflict(folder, role, Way.INSIDE) }
        return null
    }
}
