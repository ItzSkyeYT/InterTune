/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.runtime.mutableStateOf
import com.dd3boh.outertune.recap.RecapNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The note to next year's you on its slide (NOTE_WRITE) and last year's read back (NOTE_READ): the
 * story waiting while it is written, its paper's hand, and the rules the screen keeps, read from
 * its sources. Made-up words only.
 */
class RecapNoteSlidesTest {
    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the story waits while the note has the keyboard or holds words, and not once it is sealed`() {
        val note = NoteDraft(mutableStateOf(""))
        assertFalse(note.writing)
        note.focused = true
        assertTrue(note.writing)
        note.focused = false
        note.text = "Dear me"
        assertTrue(note.writing)
        note.sealing = true
        assertTrue(note.writing)
        note.sealing = false
        note.sealed(20_444L, 1_234L)
        assertTrue(note.shut)
        assertFalse(note.writing)
        assertEquals("", note.text)
        assertEquals(20_444L, note.sealedDay)
        // Written again, it takes the old one's place: a field again, and the story waits for it as before.
        note.again = true
        assertFalse(note.shut)
        note.focused = true
        assertTrue(note.writing)
    }

    @Test
    fun `a long note is written a little smaller so that it fits, never below 15 sp`() {
        assertEquals(19f, noteSize(0), 0f)
        assertEquals(19f, noteSize(180), 0f)
        assertTrue(noteSize(340) in 15f..19f)
        assertEquals(15f, noteSize(RecapNote.MAX_CHARS), 0.01f)
        assertEquals(15f, noteSize(900), 0f)
        // Its lines as far apart as the design's 19 on 30, whatever its size.
        assertEquals(30f, noteHand(androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)).lineHeight.value, 0.01f)
    }

    @Test
    fun `the story tells its stage at every frame, and notes are sealed and opened off the main thread`() {
        val screen = code("ui/screens/recap/RecapScreen.kt")
        val loop = screen.substringAfter("LaunchedEffect(stage) {\n        var last").substringBefore("\n    }\n")
        assertTrue("stage.writing = p.kind == SlideKind.NOTE_WRITE && note.writing" in loop)
        assertTrue("withContext(Dispatchers.IO) { RecapNote.save(context, recap.year, text, day) }" in screen)
        assertTrue("withContext(Dispatchers.IO) { RecapNote.read(context, recap.year) }" in screen)
        // Last year's note is opened once, as the Recap is read.
        assertTrue("withContext(Dispatchers.IO) { RecapNote.read(context, year - 1) }" in code("viewmodels/RecapViewModel.kt"))
        // While the field has the keyboard, a tap on the story closes it rather than turning the page.
        val tap = screen.substringAfter(".pointerInput(slides) {")
        assertTrue(tap.indexOf("if (note.focused) {") in 0 until tap.indexOf("if (back) previous() else next()"))
        // And Back closes the keyboard, then lets go of the field, before it closes the Recap.
        val slide = code("ui/screens/recap/RecapNoteSlides.kt")
        assertTrue("ScreenBackHandler(enabled = note.focused) { focusManager.clearFocus() }" in slide)
        assertTrue("LaunchedEffect(keyboard) { if (!keyboard && note.focused) focusManager.clearFocus() }" in slide)
        assertTrue("note.text = it.take(RecapNote.MAX_CHARS)" in slide)
    }

    @Test
    fun `with the keyboard up, Seal it and Not now stand just above it, and the song's pill stays behind the keys`() {
        val slide = code("ui/screens/recap/RecapNoteSlides.kt")
        // The note is written in a column of its own: in the slides' plain one the keyboard hid both buttons (Pixel 5, 22 Nov 2026).
        val write = slide.substringAfter("internal fun NoteWriteSlide(").substringBefore("\n}\n")
        assertTrue("NoteColumn(keyboard, pinned = {" in write)
        assertFalse("SlideColumn {" in write)
        val column = slide.substringAfter("private fun NoteColumn(").substringBefore("\n}\n")
        // Its zoom is worked out on the room without the keyboard, so nothing changes size as it opens...
        assertFalse("safeDrawing" in column)
        assertTrue(column.indexOf("RecapFit.zoom(") in 0 until column.indexOf("WindowInsets.ime"))
        // ...and its room ends at the keyboard's top, the bars and the pill's place left out, the buttons under what scrolls.
        assertTrue(".consumeWindowInsets(PaddingValues(top = 62.dp, bottom = 76.dp))" in column)
        assertTrue("windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))" in column)
        assertTrue(column.indexOf(".weight(1f)") in 0 until column.indexOf("if (keyboard) Box(Modifier.padding(horizontal = 24.dp)) { pinned() }"))
        // The pill keeps its place on the screen: behind the keys rather than over Seal it.
        val pill = code("ui/screens/recap/RecapScreen.kt").substringAfter("private fun SongPill(").substringBefore("\n}\n")
        assertTrue("WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)" in pill)
        assertFalse("safeDrawing" in pill)
    }
}
