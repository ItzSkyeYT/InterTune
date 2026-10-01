/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.work.ExistingPeriodicWorkPolicy
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Names backup files and the temporary files they are written as, decides which old ones to
 * remove, whether a run should write at all, and whether the schedule is kept or replaced.
 * Nothing in here needs a device, so it is tested.
 *
 * The name is the same one the manual Backup entry has produced since the beginning, which is what
 * lets the single Restore path accept a scheduled file without knowing where it came from. The
 * timestamp is part of the name rather than read from the file system because a folder chosen
 * through the document picker does not promise reliable modification times, and because a file
 * copied elsewhere and back should still count as the age it says it is.
 */
object AutoBackupPolicy {
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private const val PARTIAL = ".partial"
    private const val INTERVAL_TAG = "auto_backup_interval_hours="

    /**
     * App name, database version, fourteen digit timestamp. The name is anchored to the app doing
     * the pruning, taken literally, because this shape is inherited from OuterTune and shared with
     * the debug build: a folder holding their files too must never have them counted or removed.
     *
     * Optionally followed by " (1)", " (2)" and so on, which is what Android's own storage provider
     * does to a name that is already taken instead of refusing the file. Two runs in the same second
     * produce one, and they used to be invisible here, so they never counted towards Keep and were
     * never removed. Only that exact shape: a space, a number in brackets, nothing else.
     */
    private fun pattern(appName: String): Regex =
        Regex("^" + Regex.escape(appName) + """_(\d+)_(\d{14})(?: \((\d+)\))?\.backup$""")

    fun fileName(appName: String, dbVersion: Int, time: LocalDateTime): String =
        "${appName}_${dbVersion}_${time.format(stamp)}.backup"

    /** Only files written under [appName]: anything else in the folder is the user's and is never touched. */
    fun isBackup(appName: String, name: String): Boolean = pattern(appName).matches(name)

    /**
     * What a backup called [name] is written as until it is whole, then renamed from. It is not a
     * backup's name, so a half-written file is never counted towards Keep and never looks like
     * something to restore, even when the app is killed mid-write and nothing is left to clean up.
     */
    fun partialName(name: String): String = "$name$PARTIAL"

    /** A [partialName] of this app's, with the " (1)" Android's provider puts before the last dot. */
    private fun partialPattern(appName: String): Regex =
        Regex("^" + Regex.escape(appName) + """_(\d+)_(\d{14})\.backup(?: \(\d+\))?""" + Regex.escape(PARTIAL) + "$")

    /**
     * Half-written backups under [appName] that nothing is still writing: what a run killed
     * mid-write leaves behind. Only those started over an hour before [now] by the time in their
     * name, since a write takes seconds; that keeps this from ever touching one in progress, even
     * from another device writing into a synced folder. Nobody else's are ever returned.
     */
    fun leftoverPartials(appName: String, names: List<String>, now: LocalDateTime): List<String> {
        val ours = partialPattern(appName)
        // Same length digits, so comparing the text is comparing the time.
        val cutoff = now.minusHours(1).format(stamp)
        return names.filter { name -> ours.matchEntire(name)?.let { it.groupValues[2] < cutoff } == true }
    }

    /**
     * The backups written under [appName] to delete so that at most [keep] remain, oldest first by
     * the time in the name, and a renamed copy after the file whose name it could not have.
     *
     * Asking to keep none is refused and treated as one: a pruning step that could delete the file
     * it has just written would turn "back up automatically" into "delete my backups".
     */
    fun toDelete(appName: String, names: List<String>, keep: Int): List<String> {
        val kept = keep.coerceAtLeast(1)
        val ours = pattern(appName)
        val backups = names.mapNotNull { name ->
            ours.matchEntire(name)?.let { match ->
                val copy = match.groupValues[3]
                // No number is the original. A number too long to read is still a later copy.
                Backup(name, match.groupValues[2], if (copy.isEmpty()) 0 else copy.toIntOrNull() ?: Int.MAX_VALUE)
            }
        }
        if (backups.size <= kept) return emptyList()
        // Same length digits, so sorting the text is sorting the time.
        return backups
            .sortedWith(compareBy<Backup>({ it.stamp }, { it.copy }))
            .take(backups.size - kept)
            .map { it.name }
    }

    private class Backup(val name: String, val stamp: String, val copy: Int)

    /**
     * Whether a run should write nothing, because the backup it is for has already been made.
     *
     * A scheduled run ([manual] false) is for one backup per interval. It writes nothing when the
     * last backup is younger than three quarters of [intervalHours]. WorkManager never starts a
     * period early, so a run that is due is always a whole interval after the last one and is never
     * caught by this; what is caught is a second start of the same period, which does happen: every
     * app launch used to force one, and WorkManager restarts a run it has stopped even when that run
     * went on to finish its file. The quarter is slack for a clock that was nudged, since skipping a
     * real run costs a whole extra interval without a backup. A last backup in the future means the
     * clock was moved back, and waiting for it to catch up could be months, so it counts as old.
     *
     * Back up now ([manual] true) is for one backup made after the button was pressed at
     * [requestedAt]. It writes unless one that started at or after that moment has already
     * succeeded, which only happens when WorkManager starts the same request a second time. A
     * request with no time on it, from before this was recorded, always writes.
     *
     * [lastBackupAt] is when the last successful backup started, 0 for never.
     */
    fun shouldSkip(manual: Boolean, requestedAt: Long, lastBackupAt: Long, now: Long, intervalHours: Int): Boolean {
        if (lastBackupAt <= 0L) return false
        if (manual) return requestedAt > 0L && lastBackupAt >= requestedAt
        val age = now - lastBackupAt
        if (age < 0L) return false
        val interval = TimeUnit.HOURS.toMillis(intervalHours.coerceAtLeast(1).toLong())
        return age < interval / 4 * 3
    }

    /**
     * The tag the periodic schedule carries, saying which interval it was made for. WorkManager
     * 2.8.1 has no other way to ask (WorkInfo only gained the period in 2.9). It is stored with the
     * schedule in WorkManager's database, so changing its format replaces every schedule once.
     */
    fun intervalTag(hours: Int): String = "$INTERVAL_TAG$hours"

    /** The interval [intervalTag] put among [tags], or null when it is not there. */
    fun taggedInterval(tags: Collection<String>): Int? =
        tags.firstNotNullOfOrNull { tag ->
            if (tag.startsWith(INTERVAL_TAG)) tag.removePrefix(INTERVAL_TAG).toIntOrNull() else null
        }

    /**
     * How AutoBackup.schedule enqueues the schedule for an interval of [hours], given the tags of
     * the one there now ([scheduledTags], null when there is none).
     *
     * KEEP when the one there was made for [hours], which on almost every launch it was. Replacing
     * it (UPDATE) makes a run due at once in WorkManager 2.8.1, whatever the interval, and doing
     * that on every launch was a backup at every launch. KEEP also when there is none, where it
     * simply enqueues.
     *
     * UPDATE when it was made for another interval, or does not say (scheduled before the tag), so
     * that the schedule follows the settings, which are what the user sees. Restore is why: it
     * writes the backup's settings and restarts the app, and the schedule from before goes on at
     * the old interval while Settings shows the restored one. The launch after the restart puts
     * that right, as it would any other way the two came apart. The run a replace starts at once
     * still goes through shouldSkip.
     */
    fun schedulePolicy(scheduledTags: Collection<String>?, hours: Int): ExistingPeriodicWorkPolicy =
        if (scheduledTags == null || taggedInterval(scheduledTags) == hours) ExistingPeriodicWorkPolicy.KEEP
        else ExistingPeriodicWorkPolicy.UPDATE
}
