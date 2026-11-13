/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.downloadManager.DownloadsSimpleLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The simpler ways to download songs and to choose where they go (DownloadsSimpleLook, 12 Nov
 * 2026): three looks were drawn to be chosen from pictures, and he chose C. It is the page in the
 * builds that show held features (Unreleased.DOWNLOADS_SIMPLE); A and B were taken out. It keeps
 * everything the page of today can do, and its words are held from translators with their
 * French beside them. The rules are tried as they are; the wiring is read from the source, as the
 * other tests of this page do.
 */
class DownloadsSimpleTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")
    private val look = File(sources, "playback/downloadManager/DownloadsSimpleLook.kt").readText()
    private val says = File(sources, "playback/downloadManager/DownloadSays.kt").readText()
    private val util = File(sources, "playback/DownloadUtil.kt").readText()
    private val frag = File(sources, "ui/screens/settings/fragments/DownloadsFrag.kt").readText()
    private val simple = File(sources, "ui/screens/settings/fragments/DownloadsSimple.kt").readText()
    private val menu = File(sources, "ui/menu/GridMenu.kt").readText()
    private val en = File("src/main/res/values/strings-ot.xml").readText()
    private val fr = File("src/main/res/values-fr/strings-ot.xml").readText()

    // ---- C, his pick of 12 Nov 2026 ("go with C for downloading"), wherever the switch is on

    @Test
    fun `without the switch the page is today's`() {
        assertEquals(DownloadsSimpleLook.TODAY, DownloadsSimpleLook.pick(false))
    }

    @Test
    fun `with the switch the page is C, and no file decides it any more`() {
        assertEquals(DownloadsSimpleLook.C, DownloadsSimpleLook.pick(true))
        assertFalse("no file is read", "filesDir" in look || "readText" in look)
    }

    @Test
    fun `the switch is the preview builds', and the look is read through it`() {
        val flags = File(sources, "constants/Unreleased.kt").readText()
        assertTrue(Regex("""^\s*val DOWNLOADS_SIMPLE = BuildConfig\.PREVIEW_FEATURES\s*$""", RegexOption.MULTILINE).containsMatchIn(flags))
        assertTrue("the look is the switch's", "pick(Unreleased.DOWNLOADS_SIMPLE)" in look)
        // Nothing else decides a look: no other file reads the switch or names a look by itself.
        val readers = sources.walkTopDown().filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.DOWNLOADS_SIMPLE" in it.readText() }.map { it.name }.toSet()
        assertEquals(setOf("DownloadsSimpleLook.kt"), readers)
    }

    // ---- what C changes

    @Test
    fun `C has the plain words, the one row and says where the songs went, and today's page has none of it`() {
        fun traits(l: DownloadsSimpleLook) = listOf(l.plainWords, l.oneRow, l.saysWhenDone)
        assertEquals(listOf(false, false, false), traits(DownloadsSimpleLook.TODAY))
        assertEquals(listOf(true, true, true), traits(DownloadsSimpleLook.C))
        // A and B were taken out once he chose (12 Nov 2026).
        assertEquals(listOf("TODAY", "C"), DownloadsSimpleLook.entries.map { it.name })
    }

    @Test
    fun `a folder is named the way a person names it, without the phone's own start of the path`() {
        assertEquals("Music/InterTune", DownloadsSimpleLook.folderName("/storage/emulated/0/Music/InterTune"))
        assertEquals("Intertune/Intertune Downloads", DownloadsSimpleLook.folderName("/storage/emulated/0/Intertune/Intertune Downloads/"))
        // A card, or an address that is not a path, is left as it is.
        assertEquals("/storage/1A2B-3C4D/Music", DownloadsSimpleLook.folderName("/storage/1A2B-3C4D/Music"))
        assertEquals("content://tree/primary%3AMusic", DownloadsSimpleLook.folderName("content://tree/primary%3AMusic"))
        // The phone's storage itself has no shorter name.
        assertEquals("/storage/emulated/0", DownloadsSimpleLook.folderName("/storage/emulated/0"))
    }

    @Test
    fun `the songs in the folder are the downloads that are not inside the app, never below none`() {
        assertEquals(357, DownloadsSimpleLook.inFolder(downloads = 360, inside = 3))
        assertEquals(0, DownloadsSimpleLook.inFolder(downloads = 2, inside = 3))
    }

    // ---- nothing the page of today can do is gone in C

    @Test
    fun `the row of today stays for today's page, and the one row opens the choice of two`() {
        // Today's row first in the file, as the tests of the folder that cannot be opened read it.
        assertTrue(frag.indexOf("settingRow(R.string.dl_main_path_title)") in 0 until frag.indexOf("WhereDownloadsGoRow("))
        val row = frag.substringAfter("WhereDownloadsGoRow(").substringBefore("\n        )\n")
        assertTrue("found in Settings' search as today's row is", simple.contains("modifier = Modifier.settingRow(R.string.dl_main_path_title),"))
        // The tour's mark is written once, on today's row (SettingsCloserLookTest): it moves with the look that is chosen.
        assertFalse(simple.contains("tourTarget("))
        assertTrue("the choice of two in place of the folder dialog", row.contains("onClick = { showWhereChoice = true },"))
        assertTrue("the move, with the question of today before it", row.contains("onMove = askToMove"))
    }

    @Test
    fun `the move leaves Advanced only where the one row offers it, and asks today's question`() {
        val advanced = frag.substringAfter("SettingsClickToReveal(stringResource(R.string.advanced)) {").substringBefore("* Dialogs")
        assertTrue(advanced.contains("if (!look.oneRow) {"))
        assertTrue(advanced.contains("settingRow(R.string.dl_migrate_title)"))
        val migrate = advanced.substringAfter("settingRow(R.string.dl_migrate_title)").substringBefore("isEnabled = ")
        assertTrue("today's page keeps today's row and question", migrate.contains("stringResource(R.string.dl_migrate_description)") && migrate.contains("showMigrationDialog = true"))
        assertFalse("with no word of the looks that are gone", migrate.contains("look."))
        val ask = frag.substringAfter("val askToMove: () -> Unit = {").substringBefore("\n    }\n")
        assertTrue(ask.contains("downloadUtil.downloadsInsideApp()"))
        assertTrue(ask.contains("if (inside > 0) moveHereCount = inside"))
        assertTrue("nothing to move is said, not left as a tap that does nothing", ask.contains("R.string.dl_simple_nothing_to_move"))
        // The one row offers the move while songs are inside the app and the folder opens.
        assertTrue(simple.contains("val offersMove = folder != null && state == DownloadFolderState.OPEN && inside > 0"))
    }

    @Test
    fun `the choice of two does what the folder dialog did, back inside the app or a folder with the question after`() {
        val picked = frag.substringAfter("val pickFolderAtOnce = rememberFolderPicker { uri ->").substringBefore("\n    }\n")
        assertTrue("a folder inside a scan folder is refused, as the dialog refuses it", picked.contains("FolderNesting.isSameOrInside(uri.toString(), it.toString())"))
        assertTrue(picked.contains("onDownloadPathChange(stringFromUriList(listOf(uri)))"))
        assertTrue(picked.indexOf("downloadUtil.cd()") in 0 until picked.indexOf("downloadUtil.downloadsInsideApp()"))
        assertTrue(picked.contains("moveHereCount = inside"))
        // The picker takes the folder's permission, as the dialog's does.
        val picker = simple.substringAfter("fun rememberFolderPicker(").substringBefore("\n}\n")
        assertTrue(picker.contains("takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)"))
        // Inside the app again is the dialog's Reset then OK: no folder saved, the download manager told.
        val inside = frag.substringAfter("onInside = {").substringBefore("onFolder = {")
        assertTrue(inside.contains("onDownloadPathChange(\"\")"))
        assertTrue(inside.contains("downloadUtil.cd()"))
    }

    // ---- the Download action

    @Test
    fun `while a song downloads the menu says what a tap does, in the looks only`() {
        assertEquals(2, Regex("""title = if \(DownloadsSimpleLook\.last\.plainWords\) R\.string\.cancel_download else R\.string\.downloading,""").findAll(menu).count())
    }

    @Test
    fun `where the songs went is said once they are there, for downloads asked by hand only`() {
        // The three ways a download is asked for by hand, each before the song is queued.
        assertEquals(3, Regex("""rememberAskedByHand\(""").findAll(util.substringAfter("fun download(songs: List<MediaMetadata>) {").substringBefore("private val says = DownloadSays(context)")).count())
        val idle = util.substringAfter("override fun onIdle(downloadManager: DownloadManager) {").substringBefore("override fun onDownloadChanged(")
        assertTrue("after the move, so that it says where the song is", idle.indexOf("migrateDownloads(quiet = true, only = just)") in 0 until idle.indexOf("says.whenDone(just) { localMgr.getFilePathIfExists(it) != null }"))
        assertTrue("with no folder set too", idle.contains("says.whenDone(just) { false }"))
        // Remembered only on the simpler page, and only for songs that were asked for by hand.
        val asked = says.substringAfter("fun asked(").substringBefore("\n    }\n")
        assertTrue(asked.contains("if (DownloadsSimpleLook.of(context).saysWhenDone) asked += ids"))
        assertFalse("nothing is said at the tap any more", "atTap" in says || "dl_simple_downloading" in says)
        val whenDone = says.substringAfter("fun whenDone(").substringBefore("\n    }\n")
        assertTrue(whenDone.contains("val mine = just.filter { asked.remove(it) }"))
        assertTrue(whenDone.contains("if (mine.isEmpty() || !DownloadsSimpleLook.last.saysWhenDone) return"))
    }

    // ---- the words

    private val texts = listOf(
        "dl_simple_size_folder", "dl_simple_clear_confirm", "dl_simple_nothing_to_move",
        "dl_simple_rescan_description", "dl_simple_where_title", "dl_simple_where_inside", "dl_simple_choose_again",
        "dl_simple_inside_line", "dl_simple_in_folder", "dl_simple_in_folder_line",
    )
    private val plurals = listOf("dl_simple_still_inside", "dl_simple_saved_to", "dl_simple_saved_inside")

    private fun text(xml: String, name: String): String =
        xml.substringAfter("<string name=\"$name\"", "").substringAfter(">").substringBefore("</string>")

    private fun plural(xml: String, name: String): Map<String, String> =
        Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(
            xml.substringAfter("<plurals name=\"$name\"", "").substringBefore("</plurals>")
        ).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the words are held from translators, with their French beside them`() {
        for (name in texts) {
            assertTrue(name, en.contains("<string name=\"$name\" translatable=\"false\">"))
            assertTrue("$name has no French", fr.contains("<string name=\"$name\">"))
        }
        for (name in plurals) {
            assertTrue(name, en.contains("<plurals name=\"$name\" translatable=\"false\">"))
            assertEquals(name, setOf("one", "other"), plural(en, name).keys)
            assertEquals("$name in French", setOf("one", "many", "other"), plural(fr, name).keys)
            assertEquals(plural(fr, name)["other"], plural(fr, name)["many"])
        }
    }

    @Test
    fun `they are short, two or three sentences at most, with no dash that reads as a machine's`() {
        for (xml in listOf(en, fr)) {
            val all = texts.map { text(xml, it) } + plurals.flatMap { plural(xml, it).values }
            for (said in all) {
                assertTrue(said, said.isNotBlank())
                assertFalse(said, '—' in said || '–' in said)
                assertTrue("more than three sentences: $said", said.split(Regex("""[.?!](\s|$)""")).count { it.isNotBlank() } <= 3)
            }
        }
        // Said in a toast with no name in it: two lines, 70 characters (ToastLengthTest reads only Toaster.show calls that name their text).
        for (xml in listOf(en, fr)) assertTrue(text(xml, "dl_simple_nothing_to_move").replace("\\'", "'").length <= 70)
    }
}
