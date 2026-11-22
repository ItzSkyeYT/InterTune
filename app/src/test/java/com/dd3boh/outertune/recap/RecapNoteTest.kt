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
import java.io.File
import java.nio.file.Files

/** The note for next year's you (his adds of 22 Nov 2026): sealed, kept as written, carried by backups, never sent. */
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
    fun `sealed and opened again, each year its own, the newest note of a year kept`() {
        val d = dir()
        assertTrue(RecapNote.saveIn(d, 2026, "  first  ", 20_779L))
        assertTrue(RecapNote.saveIn(d, 2026, "second", 20_780L))
        assertTrue(RecapNote.saveIn(d, 2027, "next year", 21_144L))
        assertEquals(RecapNote.Note("second", 20_780L), RecapNote.readIn(d, 2026))
        assertEquals(RecapNote.Note("next year", 21_144L), RecapNote.readIn(d, 2027))
        assertNull("no note that year", RecapNote.readIn(d, 2025))
        // Sealed: the words are not in the file as written.
        assertFalse("second" in File(d, RecapNote.NOTES_FILE).readText(Charsets.ISO_8859_1))
    }

    @Test
    fun `a backup restored elsewhere opens it, and a wrong key opens nothing`() {
        val here = dir()
        RecapNote.saveIn(here, 2026, "kept", 20_779L)
        val restored = dir()
        for (name in listOf(RecapNote.KEY_FILE, RecapNote.NOTES_FILE)) File(here, name).copyTo(File(restored, name))
        assertEquals("kept", RecapNote.readIn(restored, 2026)?.text)
        File(restored, RecapNote.KEY_FILE).writeBytes(ByteArray(32))
        assertNull(RecapNote.readIn(restored, 2026))
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
