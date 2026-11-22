/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The note for next year's you (his add of 22 Nov 2026): kept as written, sealed on the phone, never sent. */
class RecapNoteTest {
    @Test
    fun `a note comes back as it was written, line breaks and all, with its day`() {
        val note = RecapNote.Note("Dear me in 2027:\nare you still playing WARP?", 20_779L)
        assertEquals(note, RecapNote.decode(RecapNote.encode(note)))
        assertEquals(RecapNote.Note("", 1L), RecapNote.decode(RecapNote.encode(RecapNote.Note("", 1L))))
    }

    @Test
    fun `anything that is not a sealed note reads as none`() {
        assertNull(RecapNote.decode("no day here"))
        assertNull(RecapNote.decode("\nno day"))
        assertNull(RecapNote.decode("12x\ntext"))
    }

    @Test
    fun `it is sealed with the phone's own keystore, in the app's files, and nothing of it goes out`() {
        val code = File("src/main/java/com/dd3boh/outertune/recap/RecapNote.kt").readText()
        assertTrue("\"AndroidKeyStore\"" in code)
        assertTrue("AES/GCM/NoPadding" in code)
        assertTrue("File(File(context.filesDir, \"recap-notes\"), \"note-\$year.bin\")" in code)
        // Left out of Android's backups: on another phone its key does not exist.
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File("src/main/res/xml/$rules").readText()
            assertEquals(rules, if (rules == "backup_rules.xml") 1 else 2, Regex("""path="recap-notes/"""").findAll(xml).count())
        }
        for (word in listOf("http", "YouTube", "Umami", "PollChecker", "upload")) assertFalse(word, word in code)
    }
}
