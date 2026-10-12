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

    /** On in every build, and so in a release. */
    private val inEveryBuild = setOf(
        "ENGINE", "SHORT_SETUP", "LOGIN_RETURNS",
    )

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a flag is not taken for a flag or a read of one. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** Every flag with what it is set to, as written. */
    private val flags: Map<String, String> =
        Regex("""^\s*val ([A-Z][A-Z0-9_]*) = (.+?)\s*$""", RegexOption.MULTILINE)
            .findAll(code(File(sources, "constants/Unreleased.kt").readText()))
            .associate { it.groupValues[1] to it.groupValues[2] }

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
}
