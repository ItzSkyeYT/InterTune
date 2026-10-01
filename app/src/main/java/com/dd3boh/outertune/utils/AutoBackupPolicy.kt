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
     * Half-written backups under [appName] taken to be left over: what a run killed mid-write
     * leaves behind. Nobody else's are ever returned.
     *
     * In this process pruning never meets a write in progress, whatever its age, because the two
     * both hold AutoBackup.folderLock. The hour is for anything else writing under this app's
     * name, such as the app on another device syncing the same folder: only those started over an
     * hour before [now] by the time in their name are returned, since a write takes seconds. That
     * only holds within one time zone and away from a clock change. The name carries local
     * wall-clock time, so a write from a device in a zone behind this one, or one started just
     * before the clocks went forward, looks older than it is and can be taken for a leftover while
     * it is still being written.
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

    /**
     * What a Keep change the user agreed to deletes: what [toDelete] picks now, but only those of
     * them in [confirmed], the ones counted when they were asked. The folder can change in between,
     * and a backup written meanwhile puts one more over the line; that one waits for the next
     * backup's pruning rather than going without a word.
     */
    fun confirmedToDelete(appName: String, names: List<String>, keep: Int, confirmed: Set<String>): List<String> =
        toDelete(appName, names, keep).filter { it in confirmed }

    private class Backup(val name: String, val stamp: String, val copy: Int)

    /** What letting go of the Keep slider at a new value does. */
    sealed interface KeepChange {
        /** The new value is saved and nothing is deleted now. */
        data object Save : KeepChange

        /** Ask before deleting [doomed], which is what the new value deletes from the folder as it is. */
        data class AskCount(val doomed: List<String>) : KeepChange

        /** Ask without a number: the folder could not be read, so nothing was counted. */
        data object AskUnknown : KeepChange
    }

    /**
     * What a Keep change from [oldKeep] to [newKeep] does. [count] is what AutoBackup.wouldDelete
     * finds in the folder at [newKeep], null when the folder could not be listed. It is only asked
     * for when the answer depends on it.
     *
     * Raising Keep, or leaving it, saves, without asking and without deleting anything at that
     * moment, whatever the folder holds and whether or not it can be read. The next backup prunes
     * to the saved Keep as it always has.
     *
     * Lowering it is the only change that deletes at once, and only after asking: with the number
     * when there are backups to lose, and without one when the folder could not be read (provider
     * offline, card removed, grant lost). That is not the same as nothing to lose, because the next
     * backup that reaches the folder deletes down to the new Keep. With nothing to lose it saves.
     *
     * Inline so that [count], which lists the folder, can suspend.
     */
    inline fun keepChange(oldKeep: Int, newKeep: Int, count: () -> List<String>?): KeepChange {
        if (newKeep >= oldKeep) return KeepChange.Save
        val doomed = count() ?: return KeepChange.AskUnknown
        return if (doomed.isEmpty()) KeepChange.Save else KeepChange.AskCount(doomed)
    }

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
     * Whether a change in Settings should make one backup in [folder] at once
     * (AutoBackup.backUpToNewFolder) instead of leaving it to the schedule. [wasOn] and [on] are
     * the switch before and after, [setFolder] the folder set before, and [lastBackupFolder] the
     * one the last recorded backup went to, empty when none is on record.
     *
     * Picking another folder while automatic backups are on: the schedule keeps its own time, so
     * the new folder would otherwise sit empty until the next backup is due, which can be a week
     * or a year away.
     *
     * Turning them on, with the switch or by picking the folder the switch asked for, when the last
     * backup went to another folder or none is on record. Turning on makes a new schedule whose
     * first run is due at once, but that run skips when the last backup is recent (shouldSkip),
     * wherever it went, so a folder it never reached stayed empty for a whole interval. When the
     * last backup did go to [folder], that run, or the backup already there, covers it. A backup
     * from before the folder was recorded counts as none on record, which costs at most one backup
     * more.
     *
     * When this and the schedule's first run both start, whichever takes the folder second
     * normally finds the other's backup and skips (shouldSkip), so the folder gets one backup, not
     * two. Normally, because the schedule's run only counts as that backup for this one when it
     * started writing after this one was asked for, which it almost always does, since WorkManager
     * has to start it first.
     */
    fun backUpAtOnce(wasOn: Boolean, on: Boolean, folder: String, setFolder: String, lastBackupFolder: String): Boolean {
        if (!on || folder.isBlank()) return false
        return if (wasOn) folder != setFolder else folder != lastBackupFolder
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
