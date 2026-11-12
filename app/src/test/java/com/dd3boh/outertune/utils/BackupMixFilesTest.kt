/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The app's own backup carries Mix's saved transitions and beat fixes beside the settings and the
 * database (DESIGN.md 5: they travel with backups). They live in files, not in the database, and
 * the backup held only the other two, so a restore brought back every Mix switch with no saved pair.
 *
 * The backup itself copies the library under a Room transaction and writes the settings through
 * DataStore, which take a phone to run: what goes into the zip is held here through
 * BackupWriter.addFiles, and the backup's call to it is read from the source.
 */
class BackupMixFilesTest {

    private fun phone(): File = Files.createTempDirectory("backup-mix").toFile().apply { deleteOnExit() }

    private fun zipOf(filesDir: File): ByteArray =
        ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { BackupWriter.addFiles(it, filesDir) } }.toByteArray()

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

    private val writer = File("src/main/java/com/dd3boh/outertune/utils/BackupWriter.kt").readText()

    @Test
    fun `a backup carries the saved transitions and the beat fixes`() {
        val from = phone()
        File(from, "mix").mkdirs()
        val transitions = """{"v":1,"transitions":[{"pair":"a>b"}]}"""
        val fixes = """{"v":1,"fixes":{"a":{"bpm":76.0}}}"""
        File(from, "mix/transitions.json").writeText(transitions)
        File(from, "mix/beat-fixes.json").writeText(fixes)

        val written = entries(zipOf(from))

        assertEquals(listOf("mix/transitions.json", "mix/beat-fixes.json"), written.keys.toList())
        assertEquals(transitions, String(written.getValue("mix/transitions.json")))
        assertEquals(fixes, String(written.getValue("mix/beat-fixes.json")))

        // A restore on a new phone puts them back where the stores read them.
        val to = phone()
        BackupWriter.restoreFiles(to, written.filterKeys { it in BackupWriter.FILES })
        assertEquals(transitions, File(to, "mix/transitions.json").readText())
        assertEquals(fixes, File(to, "mix/beat-fixes.json").readText())
    }

    @Test
    fun `the backup puts them in with the library, which a restore puts them back with`() {
        val library = writer.indexOf("if (copy != null) {")
        assertTrue(library >= 0)
        assertTrue(writer.indexOf("addFiles(zip, context.filesDir)") in library until writer.indexOf("} finally {", library))
    }

    @Test
    fun `a backup made with nothing saved for Mix still writes, and carries no Mix file`() {
        assertEquals(emptyList<String>(), entries(zipOf(phone())).keys.toList())
    }
}
