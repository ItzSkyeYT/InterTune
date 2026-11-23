/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import com.dd3boh.outertune.recap.RecapNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Your notes": every note a Recap has opened, newest Recap first, and those for a Recap still to
 * come sealed, counted and never opened. Made-up words only.
 */
class RecapNotesPageTest {
    @Test
    fun `notes a Recap has opened show, newest Recap first, and those for one to come stay sealed and only counted`() {
        val asked = mutableListOf<Int>()
        val open = { kept: Int -> asked += kept; listOf(RecapNote.Note("from $kept", kept * 10L)) }
        // Sealed by the Recaps of 2025, 2026 and 2027, for those of 2026, 2027 and 2028; on show: 2026's.
        val shelf = notesShelf(mapOf(2025 to 2, 2026 to 3, 2027 to 1), upTo = 2026, open = open)
        assertEquals(
            listOf(NotesShelf.Sealed(2028, 1), NotesShelf.Sealed(2027, 3), NotesShelf.Opened(2026, listOf(RecapNote.Note("from 2025", 20_250L)))),
            shelf,
        )
        // The sealed ones are never opened, not even to be counted.
        assertEquals(listOf(2025), asked)
    }

    @Test
    fun `once the next year's Recap is out its notes open, and before any Recap nothing does`() {
        val open = { kept: Int -> listOf(RecapNote.Note("from $kept", 1L)) }
        assertTrue(notesShelf(mapOf(2026 to 1), upTo = 2026, open = open).single() is NotesShelf.Sealed)
        assertTrue(notesShelf(mapOf(2026 to 1), upTo = 2027, open = open).single() is NotesShelf.Opened)
        assertTrue(notesShelf(mapOf(2026 to 1), upTo = null, open = open).single() is NotesShelf.Sealed)
        assertTrue(notesShelf(emptyMap(), upTo = 2027, open = open).isEmpty())
        // A year whose notes do not open (another key) shows nothing rather than an empty paper.
        assertTrue(notesShelf(mapOf(2025 to 2), upTo = 2027) { emptyList() }.isEmpty())
    }

    @Test
    fun `the page reads off the main thread, and opens from the start screen and from the Stats page's banner`() {
        fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
        assertTrue("withContext(Dispatchers.IO) { notesShelf(RecapNote.years(context), RecapNotesOpen.upTo(context)) { RecapNote.read(context, it) } }" in code("ui/screens/recap/RecapNotesPage.kt"))
        assertTrue("OpenRow(s, onOpen, onNotes)" in code("ui/screens/recap/RecapStart.kt"))
        assertTrue("if (notesOpen) RecapNotesDialog(onClose = { notesOpen = false })" in code("ui/screens/recap/RecapScreen.kt"))
        assertTrue("onNotes = { notesOpen = true })" in code("ui/screens/StatsScreen.kt"))
        assertTrue("if (notesOpen) RecapNotesDialog(onClose = { notesOpen = false })" in code("ui/screens/StatsScreen.kt"))
    }
}
