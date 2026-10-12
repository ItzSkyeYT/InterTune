/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Setup's first page leads to the import: beside "I have a backup", for somebody whose music is in
 * another app and who has no backup of this one. Beside and not under: one under the other, the
 * second button took the room of the fifth line of what the app does, on the phone the page was
 * fitted to (a Pixel 5: the line was cut in English and gone in French).
 *
 * It is there only while the import is (Unreleased.LIBRARY_IMPORT), it goes to the import screen
 * the way the second page goes to the sign-in page, and the import screen left goes back to setup
 * whichever way it is left. This reads the source, as the other tests of setup do: the flag is a
 * constant of the build, and the page is drawn by Compose.
 */
class ShortSetupImportTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for the thing. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val setup = code("ui/screens/ShortSetup.kt")
    private val welcome = setup.substringAfter("PAGE_WELCOME -> SetupPage(").substringBefore("PAGE_SIGN_IN -> SetupPage(")
    private val buttons = welcome.substringAfter("buttons = {")

    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    @Test
    fun `the first page offers the import after its backup button`() {
        val backupAt = buttons.indexOf("R.string.oobe_use_backup")
        val importAt = buttons.indexOf("R.string.setup_import_playlists")
        assertTrue("the backup button was not found", backupAt >= 0)
        assertTrue("the import is not offered after it", importAt > backupAt)
        // Among the page's own buttons, which stand under what scrolls and beside it on a phone
        // on its side: not in what scrolls, where a short window would put it out of sight.
        assertFalse("R.string.setup_import_playlists" in welcome.substringBefore("buttons = {"))
    }

    @Test
    fun `only while the import itself is there`() {
        // With the import: the two buttons in one row. Without it: the backup button alone, the
        // width of the page, as it was.
        val with = buttons.substringAfter("if (Unreleased.LIBRARY_IMPORT) {").substringBefore("} else {")
        val without = buttons.substringAfter("} else {")
        assertTrue("no branch for a build with the import", "if (Unreleased.LIBRARY_IMPORT) {" in buttons && "} else {" in buttons)
        assertTrue("R.string.setup_import_playlists" in with && "R.string.oobe_use_backup" in with)
        assertTrue("R.string.oobe_use_backup" in without)
        assertFalse("R.string.setup_import_playlists" in without)
        // And nowhere else on any page.
        assertEquals(1, Regex("""setup_import_playlists""").findAll(setup).count())
    }

    @Test
    fun `the two stand side by side, each half the page and as tall as the other`() {
        val with = buttons.substringAfter("if (Unreleased.LIBRARY_IMPORT) {").substringBefore("} else {")
        assertTrue("not in a row", Regex("""\bRow\(""").containsMatchIn(with))
        assertTrue("the row is not as tall as its taller button", ".height(IntrinsicSize.Max)" in with)
        assertEquals("each takes half", 2, Regex("""Modifier\.weight\(1f\)\.fillMaxHeight\(\)""").findAll(with).count())
        // The same kind of button, both tonal: two ways of arriving with something, neither the
        // page's main thing, which is going on.
        assertEquals(2, Regex("""primary = false""").findAll(with).count())
    }

    @Test
    fun `it goes to the import screen as the next page goes to the sign-in page`() {
        val tap = """onClick = { if (live) navController.navigate("settings/backup/import") }"""
        assertTrue("not from a page on its way in or out", tap in buttons)
        // The screen it goes to is in the graph under the same flag, or the tap would crash.
        val graph = code("ui/navigation/AppNavGraph.kt")
        assertTrue(
            Regex("""if \(Unreleased\.LIBRARY_IMPORT\) \{\s*screen\("settings/backup/import"\) \{\s*LibraryImportScreen\(navController\)""")
                .containsMatchIn(graph),
        )
    }

    @Test
    fun `it waits while a backup is being restored, as the button above it does`() {
        // The app restarts when a restore is done: nothing is started from this page meanwhile.
        val with = buttons.substringAfter("if (Unreleased.LIBRARY_IMPORT) {").substringBefore("} else {")
        assertEquals(2, Regex("""enabled = !restoring""").findAll(with).count())
    }

    @Test
    fun `no button is smaller to the finger than it was`() {
        val button = setup.substringAfter("private fun SetupButton(").substringBefore("\n}\n")
        assertTrue(".heightIn(min = 48.dp)" in button)
        // Half the page is narrow for two lines of words: the padding at their sides is smaller there.
        assertTrue("PaddingValues(horizontal = 12.dp, vertical = 8.dp)" in button)
    }

    @Test
    fun `its words are in English and in French, and short`() {
        for (folder in listOf("values", "values-fr")) {
            val text = strings(folder)["setup_import_playlists"]
            assertTrue("no text in $folder", !text.isNullOrBlank())
            assertTrue("$folder: ${text!!.length} letters is long for a button", text.length <= 40)
            assertFalse(folder, '—' in text || '.' in text)
        }
        assertEquals("I have playlists in another app", strings("values").getValue("setup_import_playlists"))
        // It reads beside the button above it, which says "I have a backup".
        assertTrue(strings("values").getValue("oobe_use_backup").startsWith("I have "))
        assertTrue(strings("values-fr").getValue("setup_import_playlists").startsWith("J'ai "))
        assertTrue(strings("values-fr").getValue("oobe_use_backup").startsWith("J'ai "))
    }

    @Test
    fun `the import screen left goes back to setup, by a long press on Back too`() {
        val screen = code("ui/screens/settings/LibraryImportScreen.kt")
        // Done, the tap on Back and the system's Back all go up, which from setup is setup.
        assertTrue("paneUp?.invoke() ?: navController.navigateUp()" in screen)
        // A long press goes to the main screen everywhere else. That one is under setup, and
        // going there would leave setup undone with nothing on screen to finish it.
        assertTrue("""navController.previousBackStackEntry?.destination?.route == "setup_wizard"""" in screen)
        assertTrue("onLongBack = { if (fromSetup) navController.navigateUp() else navController.backToMain() }" in screen)
    }

    @Test
    fun `the row under the pages and the pages themselves are as they were`() {
        // Three pages, one row drawn once for all of them.
        for (page in listOf("PAGE_WELCOME -> SetupPage(", "PAGE_SIGN_IN -> SetupPage(")) assertTrue(page, page in setup)
        assertEquals(1, Regex("""\bSetupNavRow\(""").findAll(setup.substringBefore("private fun SetupNavRow(")).count())
        // The first page still has its two layouts, upright and for a window too short to stack.
        assertTrue("SetupOverview(close = beside)" in welcome)
    }

    @Test
    fun `the flag is read where its doc comment says and nowhere else`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.LIBRARY_IMPORT" in code(it.relativeTo(sources).path) }
            .map { it.relativeTo(sources).invariantSeparatorsPath }
            .toSet()
        assertEquals(
            setOf(
                "ui/screens/settings/BackupSettings.kt",
                "ui/navigation/AppNavGraph.kt",
                "ui/screens/settings/SettingsTwoPane.kt",
                "ui/screens/ShortSetup.kt",
            ),
            readers,
        )
        val note = File(sources, "constants/Unreleased.kt").readText().substringBefore("val LIBRARY_IMPORT").substringAfterLast("/**")
        for (named in listOf("BackupSettings", "the nav graph", "SettingsTwoPane", "ShortSetup")) assertTrue(named, named in note)
    }
}
