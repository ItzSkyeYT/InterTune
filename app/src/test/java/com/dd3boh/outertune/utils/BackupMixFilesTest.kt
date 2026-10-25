/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.InternalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * The app's own backup carries Mix's saved transitions and beat fixes beside the settings and the
 * database (DESIGN.md 5: they travel with backups). They live in files, not in the database, and
 * the backup held only the other two, so a restore brought back every Mix switch with no saved pair.
 */
class BackupMixFilesTest {

    private fun phone(): File = Files.createTempDirectory("backup-mix").toFile().apply { deleteOnExit() }

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                out[entry.name] = input.readBytes()
                entry = input.nextEntry
            }
        }
        return out
    }

    @Test
    fun `a backup carries the saved transitions and the beat fixes`() {
        val from = phone()
        File(from, "datastore").mkdirs()
        File(from, "datastore/${BackupWriter.SETTINGS_FILENAME}").writeText("settings")
        val database = File(from, "song.db").apply { writeText("database") }
        File(from, "mix").mkdirs()
        val transitions = """{"v":1,"transitions":[{"pair":"a>b"}]}"""
        val fixes = """{"v":1,"fixes":{"a":{"bpm":76.0}}}"""
        File(from, "mix/transitions.json").writeText(transitions)
        File(from, "mix/beat-fixes.json").writeText(fixes)

        val zip = ByteArrayOutputStream()
        BackupWriter.write(from, zip) { database.path }
        val written = entries(zip.toByteArray())

        assertEquals("settings", String(written.getValue(BackupWriter.SETTINGS_FILENAME)))
        assertEquals("database", String(written.getValue(InternalDatabase.DB_NAME)))
        assertTrue(written.keys.toString(), "mix/transitions.json" in written)
        assertEquals(transitions, String(written.getValue("mix/transitions.json")))
        assertEquals(fixes, String(written.getValue("mix/beat-fixes.json")))

        // A restore on a new phone puts them back where the stores read them.
        val to = phone()
        BackupWriter.restoreFiles(to, written.filterKeys { it in BackupWriter.FILES })
        assertEquals(transitions, File(to, "mix/transitions.json").readText())
        assertEquals(fixes, File(to, "mix/beat-fixes.json").readText())
    }

    @Test
    fun `a backup made with nothing saved for Mix still writes, and carries no Mix file`() {
        val from = phone()
        File(from, "datastore").mkdirs()
        File(from, "datastore/${BackupWriter.SETTINGS_FILENAME}").writeText("settings")
        val database = File(from, "song.db").apply { writeText("database") }
        val zip = ByteArrayOutputStream()
        BackupWriter.write(from, zip) { database.path }
        assertEquals(listOf(BackupWriter.SETTINGS_FILENAME, InternalDatabase.DB_NAME), entries(zip.toByteArray()).keys.toList())
    }
}
