/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.utils.scanners.ScanReport.Folder
import com.dd3boh.outertune.utils.scanners.ScanReport.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A scan that found nothing used to end as "0/0 songs processed" and the Scan button again
 * (a 1h20 MP3 on an SD card that never showed up, 15 Nov 2026). Each way it can end has to say so.
 */
class ScanReportTest {

    @Test
    fun `a folder that could not be opened is named, before anything else`() {
        val folders = listOf(
            Folder("Ad04a", listed = false, found = 0),
            Folder("Music", listed = true, found = 0, kept = true),
        )
        assertEquals(Note.Unreadable(listOf("Ad04a")), ScanReport.noteFor(folders, blocked = 3))
    }

    @Test
    fun `songs kept because MediaStore had nothing for their folder are said next`() {
        val folders = listOf(Folder("Ad04a", listed = true, found = 0, kept = true), Folder("Music", listed = true, found = 12))
        assertEquals(Note.Kept(listOf("Ad04a")), ScanReport.noteFor(folders, blocked = 0))
    }

    @Test
    fun `songs that were found and could not be read are said, even among songs that were`() {
        assertEquals(Note.Blocked, ScanReport.noteFor(listOf(Folder("Ad04a", listed = true, found = 6)), blocked = 6))
        assertEquals(Note.Blocked, ScanReport.noteFor(listOf(Folder("Music", listed = true, found = 41)), blocked = 2))
    }

    @Test
    fun `songs with no path to open them by say to add the folder again, before the nomedia note`() {
        // #243 (21 Nov 2026): a folder picked in Downloads on Android 9, its songs found and none of them opened.
        val folders = listOf(Folder("Music", listed = true, found = 111))
        assertEquals(Note.Elsewhere, ScanReport.noteFor(folders, blocked = 0, elsewhere = 111))
        assertEquals(Note.Elsewhere, ScanReport.noteFor(folders, blocked = 4, elsewhere = 1))
        // A folder that cannot be opened, or songs kept, still come first.
        assertEquals(Note.Unreadable(listOf("Ad04a")), ScanReport.noteFor(listOf(Folder("Ad04a", listed = false, found = 0)), 0, elsewhere = 3))
        assertEquals(Note.Kept(listOf("Ad04a")), ScanReport.noteFor(listOf(Folder("Ad04a", listed = true, found = 0, kept = true)), 0, elsewhere = 3))
        // None: the note is the one it was.
        assertEquals(Note.Blocked, ScanReport.noteFor(folders, blocked = 2, elsewhere = 0))
    }

    @Test
    fun `a scan whose folders all held nothing says so, with every name`() {
        val folders = listOf(Folder("Ad04a", listed = true, found = 0), Folder("Bob", listed = true, found = 0))
        assertEquals(Note.Empty(listOf("Ad04a", "Bob")), ScanReport.noteFor(folders, blocked = 0))
    }

    @Test
    fun `an ordinary scan says nothing`() {
        // Some folder empty among others that held songs is normal.
        val folders = listOf(Folder("Ad04a", listed = true, found = 0), Folder("Music", listed = true, found = 41))
        assertNull(ScanReport.noteFor(folders, blocked = 0))
        // No scan folder at all is the folder list's business, not a scan's.
        assertNull(ScanReport.noteFor(emptyList(), blocked = 0))
    }

    @Test
    fun `a name is said once, however many folders carry it`() {
        val twice = listOf(Folder("Music", listed = false, found = 0), Folder("Music", listed = false, found = 0))
        assertEquals(Note.Unreadable(listOf("Music")), ScanReport.noteFor(twice, blocked = 0))
    }

    @Test
    fun `the Settings scan ends with the note, after the library is told to reload`() {
        val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LocalMediaSettingsFrag.kt").readText()
        val reload = frag.indexOf("onLastLocalScanChange(LocalDateTime")
        val said = frag.indexOf("message = scanNoteText(context, note)")
        assertTrue("the Settings scan no longer builds the note", frag.contains("ScanReport.noteFor("))
        assertTrue("the Settings scan no longer says what it found", said >= 0)
        // showSnackbar waits for the message to go, up to ten seconds: said first, it would hold the reload up.
        assertTrue("the note is said before the library is told to reload", reload in 0 until said)
        // Every kind of note has its words.
        val words = frag.substringAfter("private fun scanNoteText(").substringBefore("\n}\n")
        listOf(
            "R.plurals.scan_note_unreadable", "R.string.scan_note_kept",
            "R.string.scan_note_blocked", "R.string.scan_note_empty", "R.string.scan_note_elsewhere",
        ).forEach { assertTrue("$it is not shown", words.contains(it)) }
    }

    // The words. TASTE.md, "The app's words": two short sentences at most, you and contractions, no
    // "rather than", "instead of", "which", "itself", semicolons or exact counts.

    private val en = File("src/main/res/values/strings-ot.xml").readText()
    private val fr = File("src/main/res/values-fr/strings-ot.xml").readText()

    private fun text(xml: String, name: String): String =
        Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1) ?: error("$name is missing")

    private fun plural(xml: String, name: String): Map<String, String> =
        Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(
            xml.substringAfter("<plurals name=\"$name\"").substringBefore("</plurals>")
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private val named = listOf("scan_note_kept", "scan_note_empty")

    private fun notes(xml: String) =
        named.map { text(xml, it) } + text(xml, "scan_note_blocked") + text(xml, "scan_note_elsewhere") +
            plural(xml, "scan_note_unreadable").values

    @Test
    fun `every note has its words in English and French, with the folder names in the same place`() {
        for (xml in listOf(en, fr)) {
            named.forEach { assertTrue("$it has no name in it", text(xml, it).contains("%1\$s")) }
            assertFalse("the unreadable files are not counted", text(xml, "scan_note_blocked").contains("%"))
            assertFalse("the songs with no path are not counted", text(xml, "scan_note_elsewhere").contains("%"))
            plural(xml, "scan_note_unreadable").values.forEach { assertTrue(it, it.contains("%1\$s")) }
        }
        // "it" for one folder, "them" for several.
        assertEquals(setOf("one", "other"), plural(en, "scan_note_unreadable").keys)
        assertEquals(setOf("one", "many", "other"), plural(fr, "scan_note_unreadable").keys)
        assertEquals(plural(fr, "scan_note_unreadable")["other"], plural(fr, "scan_note_unreadable")["many"])
    }

    @Test
    fun `the English notes keep to the app's words`() {
        notes(en).forEach { note ->
            val sentences = note.split(Regex("""[.!?](\s|$)""")).filter { it.isNotBlank() }
            assertTrue("$note: more than two sentences", sentences.size <= 2)
            assertTrue("$note: too long for a note", note.length <= 120)
            listOf("rather than", "instead of", "which", "itself", ";", "—", "%d").forEach {
                assertFalse("$note: $it", note.contains(it))
            }
            // An apostrophe in a resource has to be escaped, or the build stops on it.
            assertFalse("$note: apostrophe not escaped", Regex("""(?<!\\)'""").containsMatchIn(note))
        }
        // The way out for a folder that cannot be opened, as it was agreed.
        assertEquals(
            "Can\\'t open %1\$s. Remove it from the folder list and add it again.",
            plural(en, "scan_note_unreadable")["one"],
        )
    }

    @Test
    fun `the French notes say vous and keep to the same rules`() {
        notes(fr).forEach { note ->
            val sentences = note.split(Regex("""[.!?](\s|$)""")).filter { it.isNotBlank() }
            assertTrue("$note: more than two sentences", sentences.size <= 2)
            assertFalse("$note: em dash", note.contains("—"))
            assertFalse("$note: semicolon", note.contains(";"))
            assertFalse("$note: apostrophe not escaped", Regex("""(?<!\\)'""").containsMatchIn(note))
        }
        plural(fr, "scan_note_unreadable").values.forEach {
            assertTrue("$it: vous, not tu", it.contains("Retirez-") && it.contains("ajoutez-"))
        }
    }
}
