/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Tester app, InterTune Tester (dev.skye.intertune.tester): the app testers are given. Its build type is
 * called beta, since Gradle's Android plugin refuses a build type whose name starts with "test". It is the
 * Preview under a package, a name and an icon of its own, with the tester lock always on, so that a build for
 * testers never installs over the Preview that is on our own phones. Read from the build file and the source
 * sets, as the other tests of the lock are: the app is not built by a unit test.
 */
class TesterAppBuildTest {

    private val build = File("build.gradle.kts").readText()
    private val preview = build.substringAfter("create(\"preview\") {").substringBefore("create(\"beta\")")
    private val beta = build.substringAfter("create(\"beta\") {").substringBefore("create(\"userdebug\")")

    /** The build file's lines without the comments, so a sentence that names a setting is not taken for the setting. */
    private fun code(text: String) = text.lines().filter { !it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun `the beta type is the preview under the package of the Tester app, and the preview is what it was`() {
        // Three apps that install side by side, by their package: the release is the id itself.
        val suffixes = Regex("applicationIdSuffix = \"(\\.\\w+)\"").findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(listOf(".debug", ".preview", ".tester"), suffixes)
        assertTrue("initWith(getByName(\"preview\"))" in beta)
        assertTrue("applicationIdSuffix = \".tester\"" in beta)
        assertTrue("its libraries are the release's, the native TagLib among them: built with optimization, as the release's", "matchingFallbacks += listOf(\"release\")" in beta)
        assertTrue("and the list the preview handed it is emptied first", "matchingFallbacks.clear()" in beta)
        // The Preview, his own: still a debug build under .preview, with a lock only when the property says so.
        assertTrue("initWith(getByName(\"debug\"))" in preview)
        assertTrue("applicationIdSuffix = \".preview\"" in preview)
        assertTrue("matchingFallbacks += listOf(\"debug\")" in preview)
        assertTrue("testerLock(on = project.findProperty(\"intertune.testerLock\") == \"true\")" in preview)
    }

    @Test
    fun `the beta type is not debuggable and is shrunk and optimized as the release is, signed with the debug key as ever`() {
        // A debuggable app is never compiled ahead of time and runs Compose far slower than a release does: testers called
        // the Tester app choppy next to 0.12.9 (11 Nov 2026).
        val release = build.substringAfter("buildTypes {").substringBefore("        debug {")
        assertTrue("isDebuggable = false" in beta)
        assertFalse("isDebuggable = true" in beta)
        assertTrue("isMinifyEnabled = true" in beta)
        assertTrue("isShrinkResources = true" in beta)
        // The release's own rules, the same two files: the Tester app runs what the release runs through R8.
        val rules = "proguardFiles(getDefaultProguardFile(\"proguard-android-optimize.txt\"), \"proguard-rules.pro\")"
        assertTrue(rules in release)
        assertTrue(rules in beta)
        // A tester updates the app they have installed over itself, and a new key would be refused: the debug key, as every
        // Tester build has had, and never the release's.
        assertTrue("signingConfig = signingConfigs.getByName(\"debug\")" in beta)
        assertFalse("ot_release" in beta)
        assertFalse("the preview, which is a debug build, keeps its own debug-ness", "isDebuggable" in code(preview))
        assertFalse("isMinifyEnabled" in code(preview))
    }

    @Test
    fun `the features a preview shows are kept by PREVIEW_FEATURES, which the debug, preview and beta types have and a release has not`() {
        val release = build.substringAfter("buildTypes {").substringBefore("        debug {")
        val debug = build.substringAfter("        debug {").substringBefore("create(\"preview\")")
        val userdebug = build.substringAfter("create(\"userdebug\") {").substringBefore("\n        }")
        val on = "buildConfigField(\"boolean\", \"PREVIEW_FEATURES\", \"true\")"
        val off = "buildConfigField(\"boolean\", \"PREVIEW_FEATURES\", \"false\")"
        // Each type says it itself: none depends on what initWith copies from another.
        for ((name, block) in listOf("debug" to debug, "preview" to code(preview), "beta" to beta)) assertTrue("$name: PREVIEW_FEATURES on", on in block)
        for ((name, block) in listOf("release" to release, "userdebug" to userdebug)) assertTrue("$name: PREVIEW_FEATURES off", off in block)
        assertEquals(5, Regex("PREVIEW_FEATURES").findAll(code(build)).count())
        // The unreleased flags read it, not BuildConfig.DEBUG, which the beta type, not being debuggable, has off.
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
            .lines().filter { !it.trim().startsWith("*") && !it.trim().startsWith("/*") && !it.trim().startsWith("//") }
        val legacy = flags.filter { Regex("^\\s*val [A-Z][A-Z0-9_]* = BuildConfig\\.DEBUG\\s*$").matches(it) }
        assertTrue("flags still reading BuildConfig.DEBUG: $legacy", legacy.isEmpty())
    }

    @Test
    fun `the beta type keeps the preview's version name, which the bot and the door read as a tag`() {
        // <version>-preview.<stamp>: publish-preview.sh makes the release tag from it, the door compares it with the
        // newest tag for its "out of date" answer, the bot says it in #testers and the dashboard reads the stamp out of
        // it. None of them looks inside the word "preview": see experiments/features/tester-app/STATE.md.
        assertTrue("(project.findProperty(\"previewVersion\") as String?)?.let { versionNameSuffix = \"-preview.\$it\" }" in preview)
        assertTrue("versionNameSuffix = getByName(\"preview\").versionNameSuffix" in beta)
        assertFalse("the beta type sets no suffix of its own", "-preview." in beta || "-tester." in build || "-beta." in build)
    }

    @Test
    fun `the lock is always on in the beta type, with no property to turn it off and the real door unless a stand-in is asked for`() {
        assertTrue("testerLock(on = true)" in beta)
        assertFalse("the beta type reads no property of the lock's", "findProperty" in beta)
        assertFalse("and writes no value of its own", "TESTER_LOCK" in beta || "TESTER_DOOR" in beta || "manifestPlaceholders" in beta)
        // The door is the one the helper names, for both types; -Pintertune.testerDoor is its only way to change.
        val helper = build.substringAfter("fun ApplicationBuildType.testerLock(on: Boolean) {").substringBefore("\n}\n")
        assertTrue("(project.findProperty(\"intertune.testerDoor\") as String?)" in helper)
        assertTrue("?: \"https://intertune-testbuild.intertune.workers.dev\"" in helper)
    }

    @Test
    fun `the beta type takes the preview's gate, words, manifest and assets and has no code of its own`() {
        val own = File("src/beta").walkTopDown().filter { it.isFile }.map { it.extension }.toSet()
        assertTrue("src/beta holds a name and an icon, no code: $own", own.none { it in setOf("kt", "java") })
        assertFalse("its manifest is the preview's, with the marks publish-preview.sh reads", File("src/beta/AndroidManifest.xml").exists())
        assertTrue(File("src/preview/AndroidManifest.xml").readText().contains("intertune.testerLock"))
    }

    @Test
    fun `the Tester app is named InterTune Tester in every language`() {
        val named = Regex("<string name=\"app_name\">([^<]*)</string>").findAll(File("src/beta/res/values/app_name.xml").readText())
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("InterTune Tester"), named)
        // No language of main, of the beta type or of the words it shares with the preview names the app another way
        // (French is "the same": there is no values-fr/app_name to say otherwise), so the default is what every phone shows.
        val localised = listOf("src/main/res", "src/beta/res", "src/preview/res-lock").flatMap { root ->
            File(root).listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") }
                .flatMap { dir -> dir.listFiles().orEmpty().filter { "name=\"app_name\"" in it.readText() } }
        }
        assertTrue(localised.joinToString { it.path }, localised.isEmpty())
        // The Preview's name is what it was.
        assertTrue(">InterTune Preview<" in File("src/preview/res/values/app_name.xml").readText())
    }

    @Test
    fun `what the preview and the Tester app share is the lock's words and network rules alone, never what tells them apart`() {
        val shared = File("src/preview/res-lock")
        val files = shared.walkTopDown().filter { it.isFile }.map { it.relativeTo(shared).invariantSeparatorsPath }.sorted().toList()
        assertEquals(listOf("values-fr/strings-tester.xml", "values/strings-tester.xml", "xml/network_security_config.xml"), files)
        // The words are not also in the Preview's own folder or the beta type's: a source set that took two copies
        // would stop on duplicate resources.
        for (own in listOf("src/preview/res", "src/beta/res")) {
            assertFalse(own, File(own).walkTopDown().any { it.isFile && it.name == "strings-tester.xml" })
        }
        for (folders in listOf(listOf("src/preview/res", "src/preview/res-lock"), listOf("src/beta/res", "src/preview/res-lock"))) {
            val keys = folders.flatMap { resources(File(it)) }
            val twice = keys.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue("$folders holds a resource twice: $twice", twice.isEmpty())
        }
    }

    /** Every resource a res folder defines, by its folder's qualifier, its type and its name. */
    private fun resources(root: File): List<String> = root.walkTopDown().filter { it.isFile }.flatMap { file ->
        val folder = file.parentFile?.name.orEmpty()
        if (folder.startsWith("values")) {
            Regex("<(string|color|dimen|bool|integer|string-array|integer-array|array|plurals|style)\\s+[^>]*?name=\"([^\"]+)\"")
                .findAll(file.readText()).map { "$folder/${it.groupValues[1]}/${it.groupValues[2]}" }
        } else {
            sequenceOf("$folder/${file.nameWithoutExtension}")
        }
    }.toList()
}
