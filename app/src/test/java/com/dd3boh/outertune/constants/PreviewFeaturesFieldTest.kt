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
 * BuildConfig.PREVIEW_FEATURES, the build field every held-back flag of Unreleased.kt reads (ReleasedFlagsTest
 * checks that they do): on in the debug app and in what is made from it, the Preview (and, on the tester line, the
 * Tester app), off in a release and in userdebug, which is a release without minify. It is the one thing that keeps a
 * held-back feature out of a release, so where it is set is checked here, in the build file, as WidgetConfigThemeTest
 * reads it: a unit test cannot build another build type. A release with it on would ship everything that is held back.
 */
class PreviewFeaturesFieldTest {

    private val build = File("build.gradle.kts").readText()

    /** The lines that are not comments, so that a sentence that names the field is not taken for setting it. */
    private fun code(text: String) = text.lines().filter { !it.trim().startsWith("//") }.joinToString("\n")

    /** What a build type sets: from its header to the first closing brace that is as indented as the header. */
    private fun buildType(header: String): String {
        assertTrue("$header is not in the build file", "\n        $header {" in build)
        return code(build.substringAfter("\n        $header {").substringBefore("\n        }"))
    }

    private val on = "buildConfigField(\"boolean\", \"PREVIEW_FEATURES\", \"true\")"
    private val off = "buildConfigField(\"boolean\", \"PREVIEW_FEATURES\", \"false\")"

    @Test
    fun `a release has the field off, and so has userdebug, which is a release without minify`() {
        val release = buildType("release")
        val userdebug = buildType("create(\"userdebug\")")
        assertTrue("release: PREVIEW_FEATURES is not set off", off in release)
        assertFalse("release: PREVIEW_FEATURES is on", on in release)
        assertTrue("userdebug: PREVIEW_FEATURES is not set off", off in userdebug)
        assertFalse("userdebug: PREVIEW_FEATURES is on", on in userdebug)
    }

    @Test
    fun `the debug app has the field on, and the preview, made from the debug type, does not turn it off`() {
        val debug = buildType("debug")
        val preview = buildType("create(\"preview\")")
        assertTrue("debug: PREVIEW_FEATURES is not set on", on in debug)
        assertFalse("preview: PREVIEW_FEATURES is turned off", off in preview)
        // The preview has the field from the debug type (initWith copies what the type has set at that moment) or says it itself.
        assertTrue("preview: PREVIEW_FEATURES is neither said nor taken from debug", on in preview || "initWith(getByName(\"debug\"))" in preview)
        assertTrue(
            "debug is written after the preview, which is made from it: initWith would copy nothing",
            build.indexOf("\n        debug {") < build.indexOf("\n        create(\"preview\") {"),
        )
    }

    @Test
    fun `only the build types set the field, never defaultConfig or a flavor, which every type would take`() {
        val field = Regex("PREVIEW_FEATURES")
        val types = listOf("release", "debug", "create(\"preview\")", "create(\"userdebug\")") +
            // The tester line has a type of its own for the Tester app (a build type called beta).
            listOf("create(\"beta\")").filter { "\n        $it {" in build }
        assertEquals(
            "PREVIEW_FEATURES is set where no build type has it",
            types.sumOf { field.findAll(buildType(it)).count() },
            field.findAll(code(build)).count(),
        )
    }
}
