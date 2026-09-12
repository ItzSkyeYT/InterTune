/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
