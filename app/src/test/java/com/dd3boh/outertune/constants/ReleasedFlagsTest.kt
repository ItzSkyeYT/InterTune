/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a release has of Unreleased.kt.
 *
 * A flag that is `true` is on in every build. One that is `BuildConfig.DEBUG` is on in debug and
 * preview builds and off in a release. Which flags a release has on is a decision each time, and
 * the list here is those decisions: a flag turned on by mistake, or by a merge, fails here with
 * its name, and so does one of these turned off again. To ship a flag, set it to true in
 * Unreleased.kt and add its name here. A new flag that is held back needs nothing here.
 *
 * This reads the source, as ShortSetupHeldBackTest does, because a flag is a constant of the
 * build and a test cannot turn it.
 */
class ReleasedFlagsTest {

    /** On in every build, and so in a release. The second line went on for 0.11.5. */
    private val inEveryBuild = setOf(
        "ENGINE", "SHORT_SETUP", "LOGIN_RETURNS",
        "STAND_INS", "VISIONOS_IDENTITIES", "WEB_FALLBACK", "LANDSCAPE", "EXPLAIN_PICTURES", "LIBRARY_IMPORT",
    )

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a flag is not taken for a flag or a read of one. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun code(file: File) = code(file.readText())

    /** Every flag with what it is set to, as written. */
    private val flags: Map<String, String> =
        Regex("""^\s*val ([A-Z][A-Z0-9_]*) = (.+?)\s*$""", RegexOption.MULTILINE)
            .findAll(code(File(sources, "constants/Unreleased.kt")))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** The files that read [flag], Unreleased.kt aside. */
    private fun readers(flag: String): Set<String> = sources.walkTopDown()
        .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
        .filter { Regex("""Unreleased\.$flag\b""").containsMatchIn(code(it)) }
        .map { it.relativeTo(sources).path }
        .toSet()

    /** Whether [path] has the check [written] exactly so, with no condition added to it. */
    private fun assertWritten(path: String, written: String) =
        assertTrue("$path no longer has `$written` as it was", written in code(File(sources, path)).lines().map { it.trim() })

    @Test
    fun `a release has these flags on and no other`() {
        assertTrue("the flags of Unreleased.kt could not be read", flags.keys.containsAll(inEveryBuild))
        assertEquals(inEveryBuild.sorted(), flags.filterValues { it == "true" }.keys.sorted())
    }

    @Test
    fun `every other flag is on in debug and preview builds only`() {
        // Not false, which would take a feature from the debug build too, and nothing worked out.
        for ((flag, value) in flags - inEveryBuild) {
            assertEquals("$flag is neither on in every build nor held back", "BuildConfig.DEBUG", value)
        }
    }

    @Test
    fun `the developer's switch for the web client is not a flag to ship`() {
        // Its doc comment says never in a release. Named here so that shipping it takes more
        // than adding a name to the list above.
        assertEquals("BuildConfig.DEBUG", flags["WEB_CLIENT_FIRST"])
        assertFalse("WEB_CLIENT_FIRST" in inEveryBuild)
    }

    // ---- what went on for 0.11.5 ----
    //
    // For each: where it is read, and the check as it is written. A condition added to one of
    // those checks, another flag or the kind of build, takes the feature from a release again
    // with no flag here saying so. That is how the headers of the web client's address came to
    // hang on a developer's switch that no release has.

    @Test
    fun `a song that is gone is played from its other id in every build, and its two texts are in French`() {
        assertEquals(setOf("playback/StandIns.kt"), readers("STAND_INS"))
        assertWritten("playback/StandIns.kt", "private val enabled: () -> Boolean = { Unreleased.STAND_INS },")
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("exclusion_reason_gone", "error_cached_part_stale")) {
            assertTrue("$name is not open to translators", "<string name=\"$name\">" in english)
            assertTrue("$name has no French", "<string name=\"$name\">" in french)
        }
    }

    @Test
    fun `VISIONOS's other identities are in the chain of every build`() {
        // Read by the chain, and for the developer options' test row and the switch it stores.
        assertEquals(setOf("utils/YTPlayerUtils.kt", "playback/MusicService.kt", "ui/screens/settings/fragments/DeveloperFrag.kt"), readers("VISIONOS_IDENTITIES"))
        assertWritten("utils/YTPlayerUtils.kt", "internal var identitiesInChain: Boolean = Unreleased.VISIONOS_IDENTITIES")
    }

    @Test
    fun `the web client is in the chain of every build`() {
        assertEquals(setOf("utils/YTPlayerUtils.kt", "playback/MusicService.kt", "ui/screens/settings/fragments/DeveloperFrag.kt"), readers("WEB_FALLBACK"))
        // Not while the developer's switch asks the same client first, which is in no release,
        // and only in a build with the solver's two files, which are in every build.
        assertWritten("utils/YTPlayerUtils.kt", "val webInChain = Unreleased.WEB_FALLBACK && !trialOn && wire.canSolve()")
        assertWritten("utils/YTPlayerUtils.kt", "val trialOn = Unreleased.WEB_CLIENT_FIRST && askWebClientFirst")
        assertWritten("utils/YTPlayerUtils.kt", "if (!Unreleased.WEB_FALLBACK || !solverIsInThisBuild) return")
        for (asset in listOf("yt.solver.lib.js", "yt.solver.core.js")) {
            assertTrue("$asset is not in the build", File("src/main/assets/solver/$asset").isFile)
        }
    }

    @Test
    fun `a phone on its side gets its layout in every build`() {
        assertEquals(setOf("ui/utils/Landscape.kt"), readers("LANDSCAPE"))
        assertWritten("ui/utils/Landscape.kt", "val enabled: Boolean = Unreleased.LANDSCAPE,")
    }

    @Test
    fun `the import is in every build, in Settings and on setup's first page`() {
        assertEquals(
            setOf(
                "ui/screens/settings/BackupSettings.kt",
                "ui/navigation/AppNavGraph.kt",
                "ui/screens/settings/SettingsTwoPane.kt",
                "ui/screens/ShortSetup.kt",
            ),
            readers("LIBRARY_IMPORT"),
        )
        assertWritten("ui/screens/ShortSetup.kt", "if (Unreleased.LIBRARY_IMPORT) {")
        assertWritten("ui/navigation/AppNavGraph.kt", "if (Unreleased.LIBRARY_IMPORT) {")
    }

    @Test
    fun `the pictures in the explanations are in every build`() {
        assertEquals(
            setOf("ui/component/Explain.kt", "ui/screens/settings/RecommendationsSettings.kt", "ui/screens/settings/fragments/PlayerFrag.kt"),
            readers("EXPLAIN_PICTURES"),
        )
        assertWritten("ui/component/Explain.kt", "val pictured = picture != null && Unreleased.EXPLAIN_PICTURES")
        assertWritten("ui/screens/settings/fragments/PlayerFrag.kt", "val pictured = Unreleased.EXPLAIN_PICTURES")
        // The picture of the row over the Best recommendations controls.
        assertWritten("ui/screens/settings/RecommendationsSettings.kt", "if (Unreleased.EXPLAIN_PICTURES) {")
    }
}
