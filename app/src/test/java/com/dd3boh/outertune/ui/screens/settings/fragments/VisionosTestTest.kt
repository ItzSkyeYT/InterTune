/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisionosTestTest {

    @Test
    fun `each choice is read back from the two switches it stores`() {
        for (choice in VisionosTest.entries) {
            assertEquals(choice, VisionosTest.of(own = choice.own, all = choice.all))
        }
    }

    @Test
    fun `no choice leaves both switches on`() {
        for (choice in VisionosTest.entries) assertFalse(choice.name, choice.own && choice.all)
    }

    @Test
    fun `both switches on, as two rows once allowed, shows as every version`() {
        assertEquals(VisionosTest.ALL, VisionosTest.of(own = true, all = true))
    }

    @Test
    fun `neither switch on is off`() {
        assertEquals(VisionosTest.OFF, VisionosTest.of(own = false, all = false))
    }

    @Test
    fun `a build offers only what its chain has`() {
        assertEquals(listOf(VisionosTest.OFF, VisionosTest.OWN, VisionosTest.ALL), VisionosTest.choices(identities = true, fallback = true))
        assertEquals(listOf(VisionosTest.OFF, VisionosTest.ALL), VisionosTest.choices(identities = false, fallback = true))
        assertEquals(listOf(VisionosTest.OFF, VisionosTest.OWN), VisionosTest.choices(identities = true, fallback = false))
    }

    @Test
    fun `only a build made for testing has the row, whatever its chain has`() {
        for (identities in listOf(false, true)) {
            for (fallback in listOf(false, true)) {
                val chain = "identities $identities, fallback $fallback"
                assertFalse("a release with $chain", VisionosTest.offered(testBuild = false, identities = identities, fallback = fallback))
                assertEquals(chain, identities || fallback, VisionosTest.offered(testBuild = true, identities = identities, fallback = fallback))
            }
        }
    }

    // ---- which build it is ----
    //
    // That is a constant of the build, like the flags, and a test cannot turn it: these read the
    // source, as ShortSetupHeldBackTest does. Until 0.11.5 the two flags were off in a release
    // and kept the row and its switches out of one by themselves.

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a switch is not taken for a read of it. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the developer options ask which build it is before they show the row`() {
        val options = code("ui/screens/settings/fragments/DeveloperFrag.kt")
        val row = Regex(
            """if \(VisionosTest\.offered\(testBuild = BuildConfig\.DEBUG, identities = Unreleased\.VISIONOS_IDENTITIES, fallback = Unreleased\.WEB_FALLBACK\)\) \{""" +
                """\s+ListPreference\(\s+title = \{ Text\(stringResource\(R\.string\.visionos_test\)\) \}"""
        )
        assertTrue("the row is not behind VisionosTest.offered", row.containsMatchIn(options))
        assertEquals("the row is in the developer options once", 1, Regex("""R\.string\.visionos_test\b""").findAll(options).count())
    }

    @Test
    fun `and the player reads the two switches in such a build only`() {
        // A backup carries every setting, these two included, and can be restored into a release.
        // A release that read them would pretend VISIONOS is blocked with no row to stop it.
        val player = code("playback/MusicService.kt")
        for ((key, flag) in listOf("VisionosRefusedKey" to "WEB_FALLBACK", "OwnVisionosRefusedKey" to "VISIONOS_IDENTITIES")) {
            val read = Regex("""if \(BuildConfig\.DEBUG && Unreleased\.$flag\) \{\s+dataStore\.data\.map \{ it\[$key] \?: false }""")
            assertTrue("$key is read whatever the build", read.containsMatchIn(player))
            assertEquals("$key is read in one place", 1, Regex("""\[$key]""").findAll(player).count())
        }
        // Nothing else switches the pretending on.
        val callers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "YTPlayerUtils.kt" }
            .associate { it.relativeTo(sources).path to Regex("""\btakeVisionosForRefused\(""").findAll(code(it.relativeTo(sources).path)).count() }
            .filterValues { it > 0 }
        assertEquals(mapOf("playback/MusicService.kt" to 2), callers)
    }
}
