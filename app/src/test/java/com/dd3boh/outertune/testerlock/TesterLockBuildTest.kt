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
 * Only a preview published for testers has the tester lock, and nothing else has its code.
 *
 * The switch is a Gradle property that publish-preview.sh passes and nothing else does, read by the
 * preview build type alone. The gate, its rules, its door and its words are in src/preview; main
 * asks one question that the other build types answer with a stand-in that never holds the app.
 */
class TesterLockBuildTest {

    private val build = File("build.gradle.kts").readText()
    private val preview = build.substringAfter("create(\"preview\") {").substringBefore("create(\"userdebug\")")

    /** Every source file of a source set, by its path under src. */
    private fun files(sourceSet: String): List<File> =
        File("src/$sourceSet").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()

    @Test
    fun `the lock is on only when the property says true, and only the preview type reads it`() {
        assertTrue(preview.isNotBlank())
        assertTrue("absent, or anything but true, is off", "val testerLock = project.findProperty(\"intertune.testerLock\") == \"true\"" in preview)
        assertEquals("only the preview type reads the property", 1, Regex("findProperty\\(\"intertune\\.testerLock\"\\)").findAll(build).count())
        assertTrue(
            "the switch the app reads comes from it",
            "buildConfigField(\"boolean\", \"TESTER_LOCK\", \"Boolean.parseBoolean(\\\"\$testerLock\\\")\")" in preview,
        )
        assertTrue("and the mark publish-preview.sh reads", "manifestPlaceholders[\"testerLock\"] = testerLock.toString()" in preview)
        // No other build type, and no default, has a switch of its own.
        assertEquals(1, Regex("TESTER_LOCK").findAll(build).count())
        assertEquals(1, Regex("TESTER_DOOR").findAll(build).count())
        // Nor can the project's own properties turn it on for every build.
        assertFalse("intertune.testerLock" in File("../gradle.properties").readText())
    }

    @Test
    fun `the door is the real one unless a build asks for a stand-in`() {
        assertTrue("?: \"https://intertune-testbuild.intertune.workers.dev\"" in preview)
        assertEquals(1, Regex("intertune-testbuild").findAll(build).count())
        assertTrue("buildConfigField(\"String\", \"TESTER_DOOR\", \"String.valueOf(\\\"\$testerDoor\\\")\")" in preview)
        assertTrue("manifestPlaceholders[\"testerDoor\"] = testerDoor" in preview)
    }

    @Test
    fun `the preview's plain code goes to the preview and to unit tests, nowhere else`() {
        val sourceSets = build.substringAfter("    sourceSets {").substringBefore("\n    }\n")
        assertTrue("getByName(\"preview\") { java.srcDir(\"src/preview/jvm\") }" in sourceSets)
        assertTrue("for (tests in listOf(\"testDebug\", \"testRelease\", \"testUserdebug\"))" in sourceSets)
        // No other source set is given any of src/preview.
        assertEquals(
            List(2) { "srcDir(\"src/preview/jvm\")" },
            Regex("srcDir\\(\"src/preview[^\"]*\"\\)").findAll(build).map { it.value }.toList(),
        )
        // userdebug, release without minify, takes release's stand-in.
        assertTrue("java.srcDir(\"src/release/java\")" in sourceSets.substringAfter("getByName(\"userdebug\")").substringBefore("}"))
    }

    @Test
    fun `main, release and debug hold none of the gate's code or words`() {
        val gate = listOf(
            "TesterGate", "TesterLockSession", "TesterLockStore", "TesterDoor", "TesterLockRules", "TesterCode",
            "TesterInstall", "TesterGateScreen", "TesterMemory", "DoorAnswer", "GateMessage", "tester_lock_",
            "intertune-testbuild", "TESTER_LOCK", "TESTER_DOOR",
        )
        for (sourceSet in listOf("main", "release", "debug")) {
            val found = files(sourceSet).flatMap { file -> gate.filter { it in file.readText() }.map { "${file.path}: $it" } }
            assertTrue(found.joinToString("\n"), found.isEmpty())
        }
        // And every one of them is the preview's own (the door's address is in the build file).
        val previewSource = files("preview").joinToString("\n") { it.readText() }
        for (name in gate - "intertune-testbuild") assertTrue(name, name in previewSource)
    }

    @Test
    fun `release and debug answer with a stand-in that never holds the app`() {
        for (sourceSet in listOf("release", "debug")) {
            val dir = File("src/$sourceSet/java/com/dd3boh/outertune/testerlock")
            assertEquals(sourceSet, listOf("TesterLock.kt"), dir.list()?.sorted())
            val standIn = File(dir, "TesterLock.kt").readText()
            assertTrue(sourceSet, "fun holds(activity: ComponentActivity): Boolean = false" in standIn)
            val imports = Regex("^import (.+)$", RegexOption.MULTILINE).findAll(standIn).map { it.groupValues[1] }.toSet()
            assertEquals(sourceSet, setOf("androidx.activity.ComponentActivity", "androidx.compose.runtime.Composable"), imports)
        }
        // Any other build type the project grows would have no answer to compile against.
        val types = Regex("create\\(\"(\\w+)\"\\)").findAll(build.substringAfter("buildTypes {").substringBefore("sourceSets {"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("preview", "userdebug"), types)
    }

    @Test
    fun `main asks before anything of the app is drawn or started`() {
        val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val content = activity.substringAfter("        setContent {\n")
        val firstStatement = content.lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("if (TesterLock.holds(this@MainActivity)) return@setContent", firstStatement)
        // The one mention of the lock in main.
        val mentions = files("main").filter { "TesterLock" in it.readText() }.map { it.name }
        assertEquals(listOf("MainActivity.kt"), mentions)
    }

    @Test
    fun `the gate's words are the preview's alone, in English and French, out of Weblate's files`() {
        val names = { file: File -> Regex("<string name=\"(tester_lock_\\w+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList() }
        val english = names(File("src/preview/res/values/strings-tester.xml"))
        val french = names(File("src/preview/res/values-fr/strings-tester.xml"))
        assertTrue(english.size >= 8)
        assertEquals(english, french)
        // Every one is used, and the gate uses none it does not have.
        val gate = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterGate.kt").readText()
        val used = Regex("R\\.string\\.(tester_lock_\\w+)").findAll(gate).map { it.groupValues[1] }.toSet()
        assertEquals(english.toSet(), used)
        // No string of the gate's in the files translators get, whatever the language.
        val elsewhere = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && "tester_lock_" in it.readText() }
            .map { it.path }.filterNot { it.endsWith("strings-tester.xml") }.toList()
        assertTrue(elsewhere.toString(), elsewhere.isEmpty())
    }

    @Test
    fun `the door is asked with a client of its own that adds nothing about the phone`() {
        val door = File("src/preview/jvm/com/dd3boh/outertune/testerlock/TesterDoor.kt").readText()
        // Built here, from nothing: no interceptor, no cookie jar, no client of the app's.
        assertTrue(door.contains("OkHttpClient.Builder()\n"))
        for (added in listOf("Interceptor", "cookieJar", "newBuilder()", "Build.", "Settings.Secure", "ANDROID_ID", "Locale.getDefault")) {
            assertFalse(added, added in door)
        }
        assertTrue(".header(\"User-Agent\", USER_AGENT)" in door)
        assertTrue("const val USER_AGENT = \"InterTune tester check\"" in door)
        // Every header the request is given, which is that one.
        assertEquals(listOf("User-Agent"), Regex("\\.(?:header|addHeader)\\(\"([^\"]+)\"").findAll(door).map { it.groupValues[1] }.toList())
        // The body: the code, the build's name, its version number and the random number this install made for itself.
        assertEquals(
            listOf("code", "build", "vc", "install"),
            Regex("put\\(\"(\\w+)\"").findAll(door).map { it.groupValues[1] }.toList(),
        )
        // And the app makes its door with that client, never one of its own.
        val app = File("src/preview/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertEquals(listOf("TesterDoor(BuildConfig.TESTER_DOOR)"), Regex("TesterDoor\\([^)]*\\)").findAll(app).map { it.value }.toList())
    }

    @Test
    fun `the install number is random and nothing else, never read from the phone`() {
        val install = File("src/preview/jvm/com/dd3boh/outertune/testerlock/TesterInstall.kt").readText()
        assertTrue("from the system's secure generator" in install || "SecureRandom" in install)
        assertTrue("import java.security.SecureRandom" in install)
        // No id of the hardware, the account or the advertising profile, and no ordinary random generator.
        for (read in listOf(
            "Settings.Secure", "ANDROID_ID", "Build.", "TelephonyManager", "getSerial", "WifiManager", "MacAddress", "getMacAddress",
            "AdvertisingId", "InstanceId", "FirebaseInstallations", "AccountManager", "import java.util.Random", "kotlin.random",
            "nameUUIDFromBytes", "Context", "android.",
        )) {
            assertFalse(read, read in install)
        }
        // Kept with the rest of the lock's memory in its own file, and sent in the one request and nowhere else.
        val app = File("src/preview/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertEquals(1, Regex("TesterInstall\\.make\\(").findAll(app).count())
        assertTrue("install = prefs.getString(INSTALL, null)" in app)
    }

    @Test
    fun `the preview's manifest carries the two marks publish-preview reads`() {
        val manifest = File("src/preview/AndroidManifest.xml").readText()
        assertTrue("android:name=\"intertune.testerLock\"\n            android:value=\"\${testerLock}\"" in manifest)
        assertTrue("android:name=\"intertune.testerDoor\"\n            android:value=\"\${testerDoor}\"" in manifest)
    }
}
