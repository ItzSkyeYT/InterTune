/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Names backup files and decides which old ones to remove. No Android in here, so it is tested.
 *
 * The name is the same one the manual Backup entry has produced since the beginning, which is what
 * lets the single Restore path accept a scheduled file without knowing where it came from. The
 * timestamp is part of the name rather than read from the file system because a folder chosen
 * through the document picker does not promise reliable modification times, and because a file
 * copied elsewhere and back should still count as the age it says it is.
 */
object AutoBackupPolicy {
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * App name, database version, fourteen digit timestamp. The name is anchored to the app doing
     * the pruning, taken literally, because this shape is inherited from OuterTune and shared with
     * the debug build: a folder holding their files too must never have them counted or removed.
     */
    private fun pattern(appName: String): Regex =
        Regex("^" + Regex.escape(appName) + """_(\d+)_(\d{14})\.backup$""")

    fun fileName(appName: String, dbVersion: Int, time: LocalDateTime): String =
        "${appName}_${dbVersion}_${time.format(stamp)}.backup"

    /** Only files written under [appName]: anything else in the folder is the user's and is never touched. */
    fun isBackup(appName: String, name: String): Boolean = pattern(appName).matches(name)

    /**
     * The backups written under [appName] to delete so that at most [keep] remain, oldest first by
     * the time in the name.
     *
     * Asking to keep none is refused and treated as one: a pruning step that could delete the file
     * it has just written would turn "back up automatically" into "delete my backups".
     */
    fun toDelete(appName: String, names: List<String>, keep: Int): List<String> {
        val kept = keep.coerceAtLeast(1)
        val ours = pattern(appName)
        val backups = names.mapNotNull { name ->
            ours.matchEntire(name)?.let { name to it.groupValues[2] }
        }
        if (backups.size <= kept) return emptyList()
        // Same length digits, so sorting the text is sorting the time.
        return backups.sortedBy { it.second }.take(backups.size - kept).map { it.first }
    }
}
