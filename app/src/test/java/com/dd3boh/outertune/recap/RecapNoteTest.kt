/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.utils.BackupWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/** The notes for next year's you (his adds of 22 Nov 2026): several a year, each sealed, kept as written, carried by backups, never sent. */
class RecapNoteTest {
    private fun dir(): File = Files.createTempDirectory("recap-note").toFile().apply { deleteOnExit() }

    @Test
    fun `a note comes back as it was written, line breaks and all, with its day`() {
        val note = RecapNote.Note("Dear me in 2027:\nare you still playing WARP?", 20_779L)
        assertEquals(note, RecapNote.decode(RecapNote.encode(note)))
        assertNull(RecapNote.decode("no day here"))
        assertNull(RecapNote.decode("12x\ntext"))
    }

    @Test
    fun `each year keeps its own note, trimmed, each sealed apart`() {
        val d = dir()
        assertTrue(RecapNote.saveIn(d, 2026, "  first  ", 20_779L))
        assertTrue(RecapNote.saveIn(d, 2027, "next year", 21_144L))
        assertTrue(RecapNote.saveIn(d, 2028, "and the one after", 21_510L))
        assertEquals(listOf(RecapNote.Note("first", 20_779L)), RecapNote.readIn(d, 2026))
        assertEquals(listOf(RecapNote.Note("next year", 21_144L)), RecapNote.readIn(d, 2027))
        assertEquals(1, RecapNote.countIn(d, 2026))
        assertTrue("no note that year", RecapNote.readIn(d, 2025).isEmpty())
        assertEquals(0, RecapNote.countIn(d, 2025))
        // Sealed: the words are not in the file as written, and no two notes share an IV.
        val file = File(d, RecapNote.NOTES_FILE)
        assertFalse("next year" in file.readText(Charsets.ISO_8859_1))
        val ivs = RecapNote.unpack(file.readBytes()).values.flatten().map { it.iv.toList() }
        assertEquals(3, ivs.size)
        assertEquals(ivs.size, ivs.toSet().size)
    }

    @Test
    fun `a year takes one note, a second is refused and the first stays as it was`() {
        val d = dir()
        assertEquals(1, RecapNote.NOTES_A_YEAR)
        assertTrue(RecapNote.saveIn(d, 2026, "note 0", 20_779L))
        assertFalse(RecapNote.saveIn(d, 2026, "one too many", 20_800L))
        assertEquals(listOf("note 0"), RecapNote.readIn(d, 2026).map { it.text })
        // Another year has room of its own.
        assertTrue(RecapNote.saveIn(d, 2027, "fresh", 21_144L))
        assertEquals(1, RecapNote.countIn(d, 2027))
    }

    @Test
    fun `a file from before the limit of one keeps all its notes, and takes no more`() {
        // Up to ten a year were taken until 24 Nov 2026: such a file is still read whole.
        val d = dir()
        assertTrue(RecapNote.saveIn(d, 2026, "old note", 20_700L))
        val file = java.io.File(d, RecapNote.NOTES_FILE)
        val one = RecapNote.unpack(file.readBytes()).getValue(2026).single()
        // Three sealed under the same key, as an older build wrote a year of three notes.
        file.writeBytes(RecapNote.pack(mapOf(2026 to List(3) { one })))
        assertEquals(3, RecapNote.countIn(d, 2026))
        assertEquals(List(3) { "old note" }, RecapNote.readIn(d, 2026).map { it.text })
        assertFalse(RecapNote.saveIn(d, 2026, "a fourth", 20_800L))
        assertEquals(3, RecapNote.countIn(d, 2026))
    }

    @Test
    fun `a damaged or older file reads as no notes and never throws, and sealing again keeps it aside`() {
        val d = dir()
        assertTrue(RecapNote.saveIn(d, 2026, "kept", 20_779L))
        val file = File(d, RecapNote.NOTES_FILE)
        val whole = file.readBytes()
        val huge = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { it.writeInt(RecapNote.MAGIC); it.writeInt(1); it.writeInt(2026); it.writeInt(1); it.writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        // Cut short, run on, garbage, empty, a length no note has, and the first format (a count, no mark).
        val bad = listOf(whole.copyOf(whole.size - 7), whole + byteArrayOf(1), ByteArray(64) { 0x7F }, ByteArray(0), huge, byteArrayOf(0, 0, 0, 1, 0, 0, 7, -22, 0, 0, 0, 12))
        for (bytes in bad) {
            file.writeBytes(bytes)
            assertTrue(RecapNote.readIn(d, 2026).isEmpty())
            assertEquals(0, RecapNote.countIn(d, 2026))
        }
        // Sealing on top of one: a new file, the one that could not be read kept beside it.
        assertTrue(RecapNote.saveIn(d, 2026, "after", 20_790L))
        assertEquals(listOf(RecapNote.Note("after", 20_790L)), RecapNote.readIn(d, 2026))
        assertTrue(File(d, RecapNote.NOTES_FILE + ".unreadable").isFile)
    }

    @Test
    fun `a backup restored elsewhere opens them all, and a wrong key opens nothing`() {
        val here = dir()
        RecapNote.saveIn(here, 2026, "kept", 20_779L)
        RecapNote.saveIn(here, 2027, "and this", 21_144L)
        val restored = dir()
        for (name in listOf(RecapNote.KEY_FILE, RecapNote.NOTES_FILE)) File(here, name).copyTo(File(restored, name))
        assertEquals(listOf("kept"), RecapNote.readIn(restored, 2026).map { it.text })
        assertEquals(listOf("and this"), RecapNote.readIn(restored, 2027).map { it.text })
        File(restored, RecapNote.KEY_FILE).writeBytes(ByteArray(32))
        assertTrue(RecapNote.readIn(restored, 2026).isEmpty())
        // Still counted: a year's room is what is kept, opened or not.
        assertEquals(1, RecapNote.countIn(restored, 2026))
    }

    @Test
    fun `the app's own backup and Android's carry it, and nothing of it goes out`() {
        assertTrue(RecapNote.KEY_FILE in BackupWriter.FILES && RecapNote.NOTES_FILE in BackupWriter.FILES)
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            assertFalse(rules, "recap-notes" in File("src/main/res/xml/$rules").readText())
        }
        val code = File("src/main/java/com/dd3boh/outertune/recap/RecapNote.kt").readText()
        for (word in listOf("http", "YouTube", "Umami", "PollChecker", "upload")) assertFalse(word, word in code)
    }
}
