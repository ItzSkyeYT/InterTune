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
 * The short setup is held back behind Unreleased.SHORT_SETUP, and with the flag off nothing is
 * different: the wizard is the six pages it was, and the music permission is asked for by the
 * scan at start.
 *
 * This reads the source, because the flag is a constant of the build and a test cannot turn it
 * off. What it holds to: the flag is read in the places named here and nowhere else, each of them
 * keeps the old way in its other branch, and the old wizard does not know the flag exists.
 */
class ShortSetupHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(sources, path).readText()

    /** The text without its comments, so a sentence that names the flag is not taken for a check. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the flag is on in debug builds only`() {
        assertTrue(Regex("""val SHORT_SETUP = BuildConfig\.DEBUG\b""").containsMatchIn(code(source("constants/Unreleased.kt"))))
    }

    @Test
    fun `the flag is read where its doc comment says and nowhere else`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.SHORT_SETUP" in code(it.readText()) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(setOf("ui/navigation/AppNavGraph.kt", "ui/utils/MediaPermissionAsk.kt"), readers)
    }

    @Test
    fun `setup is the old wizard unless the flag is on`() {
        val graph = code(source("ui/navigation/AppNavGraph.kt"))
        val destination = graph.substringAfter("screen(\"setup_wizard\"").substringBefore("\n        }")
        assertTrue(
            destination,
            Regex("""if \(Unreleased\.SHORT_SETUP\) ShortSetup\([^)]*\) else SetupWizard\(navController\)""").containsMatchIn(destination),
        )
    }

    @Test
    fun `the old wizard knows nothing of the short one`() {
        val wizard = source("ui/screens/SetupWizard.kt")
        assertFalse("SHORT_SETUP" in wizard)
        assertFalse("ShortSetup" in wizard)
        assertFalse("SetupChoices" in wizard)
        // Its six pages and its exit page's five cards are still there.
        for (page in 0..5) assertTrue("page $page", Regex("""\b$page -> """).containsMatchIn(wizard))
        for (card in listOf("UpdateOptInCard()", "PollsOptInCard()", "NewsOptInCard()", "UsageCountOptInCard()", "LastFmSimilarOptInCard()")) {
            assertTrue(card, card in wizard)
        }
    }

    @Test
    fun `nothing asks for the music permission by a new way without going through the flag`() {
        // Every place that can bring the system prompt up. The ones that ask on a tap are as they
        // were. The three new ones all decide through MediaPermissionAsk, which is the only
        // reader of the flag for this, and the scan at start asks it too.
        val asking = sources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file ->
                val text = code(file.readText())
                "permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)" in text || "arrayOf(MEDIA_PERMISSION_LEVEL)" in text
            }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf(
                // On a tap of the banner over a list.
                "ui/screens/library/FolderScreen.kt",
                "ui/screens/library/LibraryAlbumsScreen.kt",
                "ui/screens/library/LibraryArtistsScreen.kt",
                "ui/screens/library/LibraryPlaylistsScreen.kt",
                "ui/screens/library/LibraryScreen.kt",
                "ui/screens/library/LibrarySongsScreen.kt",
                // On a tap of Scan.
                "ui/screens/settings/fragments/LocalMediaSettingsFrag.kt",
                // The scan at start, and with the flag on the screens that need it.
                "MainActivityUtils.kt",
                "ui/utils/MediaPermissionAsk.kt",
            ),
            asking,
        )
        val scan = code(source("MainActivityUtils.kt"))
        assertTrue(Regex("""if \(MediaPermissionAsk\.atStart\(""").containsMatchIn(scan))
    }
}
