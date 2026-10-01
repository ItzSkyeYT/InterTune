/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.work.ExistingPeriodicWorkPolicy.KEEP
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What counts as one of our backups, and which of them go when there are too many. */
class AutoBackupPolicyTest {

    private val app = "InterTune"

    private fun name(stamp: String) = "InterTune_21_$stamp.backup"

    @Test
    fun `the name is the manual format and carries the time it was written`() {
        val time = LocalDateTime.of(2026, 9, 12, 14, 5, 9)
        val name = AutoBackupPolicy.fileName(app, 21, time)

        // The manual entry builds its name with this exact pattern, and Restore relies on it.
        val manual = "InterTune_21_${time.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}.backup"
        assertEquals(manual, name)
        assertEquals("InterTune_21_20260912140509.backup", name)
        assertTrue(AutoBackupPolicy.isBackup(app, name))
    }

    @Test
    fun `an app name with underscores still names a backup`() {
        val name = AutoBackupPolicy.fileName("Inter_Tune_Debug", 21, LocalDateTime.of(2026, 1, 1, 0, 0))
        assertTrue(AutoBackupPolicy.isBackup("Inter_Tune_Debug", name))
    }

    @Test
    fun `an app name with a space still names a backup`() {
        // The debug build is called InterTune Debug.
        val name = AutoBackupPolicy.fileName("InterTune Debug", 21, LocalDateTime.of(2026, 1, 1, 0, 0))
        assertEquals("InterTune Debug_21_20260101000000.backup", name)
        assertTrue(AutoBackupPolicy.isBackup("InterTune Debug", name))
    }

    @Test
    fun `near misses are not backups`() {
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_2026091214050.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_202609121405099.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_2026091214050x.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_v21_20260912140509.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509.backup.bak"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509.zip"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509"))
        assertFalse(AutoBackupPolicy.isBackup(app, "_21_20260912140509.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_20260912140509.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, ""))
    }

    @Test
    fun `backups from other apps in the same shape are not ours`() {
        // OuterTune, which this fork inherited the name from, and the debug build both write files
        // of exactly this shape, and a shared folder is a normal thing for a user to have.
        assertFalse(AutoBackupPolicy.isBackup(app, "OuterTune_21_20260912140509.backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune Debug_21_20260912140509.backup"))
        assertFalse(AutoBackupPolicy.isBackup("InterTune Debug", name("20260912140509")))
        // The app name is taken literally, not as a pattern.
        assertFalse(AutoBackupPolicy.isBackup("Inter.Tune", "InterXTune_21_20260912140509.backup"))
        assertTrue(AutoBackupPolicy.isBackup("Inter.Tune", "Inter.Tune_21_20260912140509.backup"))
    }

    @Test
    fun `unrelated files in the folder are never returned`() {
        val names = listOf(
            "holiday.jpg",
            "notes.txt",
            "InterTune_21_20260912140509.zip",
            name("20260101000000"),
            name("20260102000000"),
            name("20260103000000"),
            "other_app_21_20260104000000.backup.old",
            "OuterTune_21_20260104000000.backup",
            "InterTune Debug_21_20260105000000.backup",
        )
        assertEquals(listOf(name("20260101000000"), name("20260102000000")), AutoBackupPolicy.toDelete(app, names, 1))
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete(app, listOf("holiday.jpg", "notes.txt"), 1))
        // Nor do they count towards Keep: three of ours and two of theirs is three, not five.
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete(app, names, 3))
    }

    @Test
    fun `the oldest go first by the time in the name, whatever order the folder lists them`() {
        val names = listOf(
            name("20260301120000"),
            name("20250101000000"),
            name("20260215080000"),
            name("20251231235959"),
        )
        assertEquals(
            listOf(name("20250101000000"), name("20251231235959")),
            AutoBackupPolicy.toDelete(app, names, 2),
        )
        assertEquals(
            listOf(name("20250101000000"), name("20251231235959"), name("20260215080000")),
            AutoBackupPolicy.toDelete(app, names, 1),
        )
    }

    @Test
    fun `keeping as many as there are, or more, deletes nothing`() {
        val names = listOf(name("20260101000000"), name("20260102000000"), name("20260103000000"))
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete(app, names, 3))
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete(app, names, 10))
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete(app, emptyList(), 5))
    }

    @Test
    fun `asking to keep fewer than one still keeps the newest`() {
        val names = listOf(name("20260103000000"), name("20260101000000"), name("20260102000000"))
        val expected = listOf(name("20260101000000"), name("20260102000000"))
        assertEquals(expected, AutoBackupPolicy.toDelete(app, names, 0))
        assertEquals(expected, AutoBackupPolicy.toDelete(app, names, -4))
        assertEquals(expected, AutoBackupPolicy.toDelete(app, names, 1))
    }

    // Copies renamed by the storage provider.

    private fun copy(stamp: String, n: Int) = "InterTune_21_$stamp ($n).backup"

    @Test
    fun `a copy the storage provider renamed is still one of ours`() {
        // What Android's own provider does with a name that is already taken, seen on the phone as
        // "InterTune Debug_23_20260929220109 (1).backup" beside the unsuffixed file.
        assertTrue(AutoBackupPolicy.isBackup("InterTune Debug", "InterTune Debug_23_20260929220109 (1).backup"))
        assertTrue(AutoBackupPolicy.isBackup(app, copy("20260912140509", 1)))
        assertTrue(AutoBackupPolicy.isBackup(app, copy("20260912140509", 12)))
    }

    @Test
    fun `things that only look like a renamed copy are not ours`() {
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509(1).backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509 ().backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509 (a).backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509 (1).backup.bak"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509 (1) (1).backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509  (1).backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260912140509 copy.backup"))
        // Another app's copy is still another app's.
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune Debug_23_20260929220109 (1).backup"))
        assertFalse(AutoBackupPolicy.isBackup(app, "OuterTune_21_20260912140509 (1).backup"))
    }

    @Test
    fun `renamed copies count towards Keep and go oldest first`() {
        val names = listOf(
            name("20260929220109"),
            copy("20260929220109", 1),
            name("20260930084500"),
            copy("20260930084500", 2),
            copy("20260930084500", 1),
            name("20260930120000"),
        )
        // Six of ours, not three: before, the copies were invisible to Keep and stayed for good.
        assertEquals(
            listOf(name("20260929220109"), copy("20260929220109", 1), name("20260930084500")),
            AutoBackupPolicy.toDelete(app, names, 3),
        )
        // Within one second the provider's number is the order they were written in, and the
        // unsuffixed name came first.
        assertEquals(
            listOf(
                name("20260929220109"), copy("20260929220109", 1), name("20260930084500"),
                copy("20260930084500", 1), copy("20260930084500", 2),
            ),
            AutoBackupPolicy.toDelete(app, names, 1),
        )
    }

    @Test
    fun `copy numbers compare as numbers`() {
        val names = listOf(copy("20260101000000", 10), copy("20260101000000", 2), name("20260101000000"))
        assertEquals(
            listOf(name("20260101000000"), copy("20260101000000", 2)),
            AutoBackupPolicy.toDelete(app, names, 1),
        )
    }

    @Test
    fun `a shared folder with every build's backups and their copies only loses ours`() {
        val preview = "InterTune Preview"
        val theirs = listOf(
            "InterTune_20_20260829151000.backup",
            "InterTune_20_20260830145000.backup",
            "InterTune_20_20260911190000 (1).backup",
            "InterTune Debug_23_20260929220109.backup",
            "InterTune Debug_23_20260929220109 (1).backup",
            "OuterTune_21_20260101000000 (1).backup",
            "InterTune Preview_25_20260929224700.backup.txt",
            "notes (1).txt",
        )
        val ours = listOf(
            "InterTune Preview_25_20260929224700.backup",
            "InterTune Preview_25_20260929225000.backup",
            "InterTune Preview_25_20260929225000 (1).backup",
            "InterTune Preview_25_20260930213000.backup",
        )
        val doomed = AutoBackupPolicy.toDelete(preview, theirs + ours, 1)
        assertEquals(ours.dropLast(1), doomed)
        assertTrue(doomed.none { it in theirs })
        // And the release build pruning the same folder leaves the Preview's and Debug's alone,
        // copies included, even though its name is the start of theirs.
        assertEquals(
            listOf("InterTune_20_20260829151000.backup", "InterTune_20_20260830145000.backup"),
            AutoBackupPolicy.toDelete("InterTune", theirs + ours, 1),
        )
    }

    // Written under a temporary name until whole.

    @Test
    fun `a backup being written is not a backup, so nothing counts it or restores it`() {
        val partial = AutoBackupPolicy.partialName(name("20260930223816"))
        assertEquals("InterTune_21_20260930223816.backup.partial", partial)
        assertFalse(AutoBackupPolicy.isBackup(app, partial))
        // Android's provider numbers a clashing temporary name before its last dot.
        assertFalse(AutoBackupPolicy.isBackup(app, "InterTune_21_20260930223816.backup (1).partial"))
        // Nor does it count towards Keep: with one real backup and Keep 1, nothing goes.
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete(app, listOf(name("20260930000000"), partial), 1))
    }

    private val at = LocalDateTime.of(2026, 9, 30, 22, 38, 16)

    @Test
    fun `half-written backups more than an hour old are left over and go`() {
        val names = listOf(
            "InterTune_21_20260929220109.backup.partial",
            "InterTune_21_20260930213815.backup.partial",
            "InterTune_21_20260929220109.backup (1).partial",
        )
        assertEquals(names, AutoBackupPolicy.leftoverPartials(app, names, at))
    }

    @Test
    fun `a write still within the hour is never taken for a leftover`() {
        val names = listOf(
            "InterTune_21_20260930223816.backup.partial",
            "InterTune_21_20260930213817.backup.partial",
            // A clock moved back makes a fresh one look like it is from the future.
            "InterTune_21_20261001080000.backup.partial",
        )
        assertEquals(emptyList<String>(), AutoBackupPolicy.leftoverPartials(app, names, at))
        // The line is an hour to the second.
        assertEquals(
            listOf("InterTune_21_20260930213815.backup.partial"),
            AutoBackupPolicy.leftoverPartials(app, listOf("InterTune_21_20260930213815.backup.partial"), at),
        )
    }

    @Test
    fun `only this app's leftovers, never backups or anyone else's files`() {
        val names = listOf(
            name("20260101000000"),
            copy("20260101000000", 1),
            "InterTune Debug_23_20260929220109.backup.partial",
            "OuterTune_21_20260101000000.backup.partial",
            "InterTune_21_20260101000000.partial",
            "InterTune_21_20260101000000.backup.partial.txt",
            "InterTune_21_20260101000000 (1).backup.partial",
            "download.partial",
            "notes.txt",
        )
        assertEquals(emptyList<String>(), AutoBackupPolicy.leftoverPartials(app, names, at))
        assertEquals(
            listOf("InterTune Debug_23_20260929220109.backup.partial"),
            AutoBackupPolicy.leftoverPartials("InterTune Debug", names, at),
        )
    }

    // Keep changing.

    /** The Preview's files on 30 Sep, as the phone listed them before the launch at 22:38. */
    private val previewFolder = listOf(
        "20260929224700", "20260929225000", "20260929225100", "20260929230500",
        "20260930083300", "20260930093100", "20260930101800", "20260930122100",
        "20260930151900", "20260930174300", "20260930174303", "20260930194248",
        "20260930194251", "20260930211700",
    ).map { "InterTune Preview_25_$it.backup" }

    @Test
    fun `a Keep high enough for everything there removes nothing`() {
        assertEquals(14, previewFolder.count { AutoBackupPolicy.isBackup("InterTune Preview", it) })
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete("InterTune Preview", previewFolder, 14))
        assertEquals(emptyList<String>(), AutoBackupPolicy.toDelete("InterTune Preview", previewFolder, 20))
    }

    @Test
    fun `lowering Keep to 5 leaves the five newest`() {
        val doomed = AutoBackupPolicy.toDelete("InterTune Preview", previewFolder, 5)
        assertEquals(previewFolder.take(9), doomed)
        assertEquals(previewFolder.takeLast(5), previewFolder - doomed.toSet())
    }

    @Test
    fun `the launch at 22_38 wrote two more and removed eleven, which is Keep 5 holding`() {
        // Its log: two files a second apart, then eleven "Removed" lines from the first run.
        val folder = previewFolder + listOf(
            "InterTune Preview_25_20260930223816.backup",
            "InterTune Preview_25_20260930223817.backup",
        )
        assertEquals(11, AutoBackupPolicy.toDelete("InterTune Preview", folder, 5).size)
    }

    @Test
    fun `raising Keep never removes anything that lowering it would keep`() {
        for (keep in 1..20) {
            val atKeep = AutoBackupPolicy.toDelete("InterTune Preview", previewFolder, keep).toSet()
            val atMore = AutoBackupPolicy.toDelete("InterTune Preview", previewFolder, keep + 1).toSet()
            assertTrue("keep $keep", atKeep.containsAll(atMore))
            assertEquals("keep $keep", maxOf(0, previewFolder.size - keep), atKeep.size)
        }
    }

    // Whether a run writes at all.

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_790_000_000_000L

    private fun scheduledSkips(lastBackupAt: Long, hours: Int = 24) =
        AutoBackupPolicy.shouldSkip(manual = false, requestedAt = 0L, lastBackupAt = lastBackupAt, now = now, intervalHours = hours)

    @Test
    fun `a scheduled run with no backup before it writes`() {
        assertFalse(scheduledSkips(0L))
        assertFalse(scheduledSkips(-1L))
    }

    @Test
    fun `a scheduled run seconds after the last backup writes nothing`() {
        // The pairs on the phone: 17:43:00 and 17:43:03, 19:42:48 and 19:42:51.
        assertTrue(scheduledSkips(now - 3_000L))
        assertTrue(scheduledSkips(now))
    }

    @Test
    fun `a scheduled run each time the app opened writes nothing inside the interval`() {
        // 22:47, 22:50, 22:51 and 23:05 on 29 Sep, then 08:33 the next morning: one backup a day.
        assertTrue(scheduledSkips(now - 3 * 60_000L))
        assertTrue(scheduledSkips(now - 18 * 60_000L))
        assertTrue(scheduledSkips(now - 9 * hour - 46 * 60_000L))
        assertTrue(scheduledSkips(now - 17 * hour))
    }

    @Test
    fun `a scheduled run on time writes`() {
        // WorkManager never starts a period early, so a real one is at least the interval later.
        assertFalse(scheduledSkips(now - day))
        assertFalse(scheduledSkips(now - day - 1))
        assertFalse(scheduledSkips(now - 30 * day))
    }

    @Test
    fun `the line is three quarters of the interval`() {
        assertTrue(scheduledSkips(now - 18 * hour + 1))
        assertFalse(scheduledSkips(now - 18 * hour))
        assertTrue(scheduledSkips(now - 4 * hour - 30 * 60_000L + 1, hours = 6))
        assertFalse(scheduledSkips(now - 4 * hour - 30 * 60_000L, hours = 6))
        assertTrue(scheduledSkips(now - 270 * day, hours = 8760))
        assertFalse(scheduledSkips(now - 274 * day, hours = 8760))
    }

    @Test
    fun `a nonsense interval is treated as an hour rather than as never`() {
        assertTrue(scheduledSkips(now - 60_000L, hours = 0))
        assertFalse(scheduledSkips(now - hour, hours = 0))
        assertFalse(scheduledSkips(now - hour, hours = -5))
    }

    @Test
    fun `a last backup in the future does not hold backups off`() {
        // The clock was moved back. Waiting for it to catch up could be months without a backup.
        assertFalse(scheduledSkips(now + hour))
        assertFalse(scheduledSkips(now + 400 * day))
    }

    @Test
    fun `Back up now always writes when it is pressed`() {
        val pressed = now - 1_000L
        // However recent the last backup, even one from this same second.
        for (last in listOf(0L, now - 30 * day, now - 5_000L, pressed - 1)) {
            assertFalse(AutoBackupPolicy.shouldSkip(manual = true, requestedAt = pressed, lastBackupAt = last, now = now, intervalHours = 24))
        }
        // A request from before this version, with no time on it, writes too.
        assertFalse(AutoBackupPolicy.shouldSkip(manual = true, requestedAt = 0L, lastBackupAt = now - 1_000L, now = now, intervalHours = 24))
    }

    @Test
    fun `Back up now started again by WorkManager does not write a second copy`() {
        // The first attempt was stopped but its write cannot be, so it finished and recorded a
        // backup that started after the press. The restarted attempt has nothing left to do.
        val pressed = now - 4_000L
        assertTrue(AutoBackupPolicy.shouldSkip(manual = true, requestedAt = pressed, lastBackupAt = pressed + 200L, now = now, intervalHours = 24))
        assertTrue(AutoBackupPolicy.shouldSkip(manual = true, requestedAt = pressed, lastBackupAt = pressed, now = now, intervalHours = 24))
    }

    // Whether a launch keeps the schedule or replaces it.

    /** The tag WorkManager adds to every request by itself: the worker's class name. */
    private val workerTag = "com.dd3boh.outertune.utils.AutoBackupWorker"

    private fun scheduled(hours: Int) = setOf(workerTag, AutoBackupPolicy.intervalTag(hours))

    @Test
    fun `the tag is stored with the schedule, so its format stays put`() {
        assertEquals("auto_backup_interval_hours=24", AutoBackupPolicy.intervalTag(24))
        assertEquals(24, AutoBackupPolicy.taggedInterval(scheduled(24)))
        assertEquals(8760, AutoBackupPolicy.taggedInterval(scheduled(8760)))
    }

    @Test
    fun `a schedule with no tag, or a garbled one, has no interval`() {
        assertNull(AutoBackupPolicy.taggedInterval(setOf(workerTag)))
        assertNull(AutoBackupPolicy.taggedInterval(emptySet()))
        assertNull(AutoBackupPolicy.taggedInterval(setOf("auto_backup_interval_hours=")))
        assertNull(AutoBackupPolicy.taggedInterval(setOf("auto_backup_interval_hours=daily")))
        assertNull(AutoBackupPolicy.taggedInterval(setOf("24")))
    }

    @Test
    fun `with nothing scheduled the schedule is enqueued, not replaced`() {
        assertEquals(KEEP, AutoBackupPolicy.schedulePolicy(null, 24))
    }

    @Test
    fun `a launch keeps a schedule made for the interval in the settings`() {
        // Almost every launch. Replacing here was the backup at every launch.
        for (hours in listOf(6, 24, 168, 720, 4380, 8760)) {
            assertEquals("every $hours h", KEEP, AutoBackupPolicy.schedulePolicy(scheduled(hours), hours))
        }
    }

    @Test
    fun `after a Restore with another interval the launch replaces the schedule`() {
        // Weekly before the restore, daily in the backup's settings, and the other way round.
        assertEquals(UPDATE, AutoBackupPolicy.schedulePolicy(scheduled(168), 24))
        assertEquals(UPDATE, AutoBackupPolicy.schedulePolicy(scheduled(24), 168))
        // A Restore with the same interval leaves it alone, when it last ran included.
        assertEquals(KEEP, AutoBackupPolicy.schedulePolicy(scheduled(24), 24))
    }

    @Test
    fun `a new interval picked in Settings replaces the schedule, the same one does not`() {
        assertEquals(UPDATE, AutoBackupPolicy.schedulePolicy(scheduled(168), 6))
        assertEquals(KEEP, AutoBackupPolicy.schedulePolicy(scheduled(6), 6))
    }

    @Test
    fun `a schedule from before the tag is replaced once, and kept from then on`() {
        // 0.10.8 to 0.11 scheduled with no tag, so its interval is unknown.
        assertEquals(UPDATE, AutoBackupPolicy.schedulePolicy(setOf(workerTag), 24))
        assertEquals(UPDATE, AutoBackupPolicy.schedulePolicy(emptySet(), 24))
        assertEquals(UPDATE, AutoBackupPolicy.schedulePolicy(setOf(workerTag, "auto_backup_interval_hours=x"), 24))
        // The replacement carries the tag.
        assertEquals(KEEP, AutoBackupPolicy.schedulePolicy(scheduled(24), 24))
    }
}
