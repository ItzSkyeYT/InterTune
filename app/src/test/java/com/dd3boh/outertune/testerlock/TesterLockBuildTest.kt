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
 * Only a build for testers has the tester lock, and nothing else has its code.
 *
 * The Preview, which is our own, has the lock only when a Gradle property says so, read by the preview
 * build type alone; the beta type, which makes the app testers are given (InterTune Tester), has it always
 * (TesterAppBuildTest). The
 * gate, its rules, its door and its words are in src/preview; main asks one question that the other
 * build types answer with a stand-in that never holds the app.
 */
class TesterLockBuildTest {

    private val build = File("build.gradle.kts").readText()
    private val preview = build.substringAfter("create(\"preview\") {").substringBefore("create(\"beta\")")
    /** The one place that writes the lock's values for the two build types that have it. */
    private val values = build.substringAfter("fun ApplicationBuildType.testerLock(on: Boolean) {").substringBefore("\n}\n")

    /** Every source file of a source set, by its path under src. */
    private fun files(sourceSet: String): List<File> =
        File("src/$sourceSet").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()

    @Test
    fun `the preview has the lock on only when the property says true, and only the preview type reads it`() {
        assertTrue(preview.isNotBlank())
        assertTrue(values.isNotBlank())
        assertTrue(
            "absent, or anything but true, is off",
            "testerLock(on = project.findProperty(\"intertune.testerLock\") == \"true\")" in preview,
        )
        assertEquals("only the preview type reads the property", 1, Regex("findProperty\\(\"intertune\\.testerLock\"\\)").findAll(build).count())
        assertTrue(
            "the switch the app reads comes from the one place that writes it",
            "buildConfigField(\"boolean\", \"TESTER_LOCK\", \"Boolean.parseBoolean(\\\"\$on\\\")\")" in values,
        )
        assertTrue("and the mark publish-preview.sh reads", "manifestPlaceholders[\"testerLock\"] = on.toString()" in values)
        // No build type, and no default, has a switch of its own: the two that have the lock call that one place.
        assertEquals(1, Regex("TESTER_LOCK").findAll(build).count())
        assertEquals(1, Regex("TESTER_DOOR").findAll(build).count())
        assertEquals(2, Regex("\\btesterLock\\(on = ").findAll(build).count())
        // Nor can the project's own properties turn it on for every build.
        assertFalse("intertune.testerLock" in File("../gradle.properties").readText())
    }

    @Test
    fun `the door is the real one unless a build asks for a stand-in`() {
        assertTrue("?: \"https://intertune-testbuild.intertune.workers.dev\"" in values)
        assertEquals(1, Regex("intertune-testbuild").findAll(build).count())
        assertTrue("buildConfigField(\"String\", \"TESTER_DOOR\", \"String.valueOf(\\\"\$door\\\")\")" in values)
        assertTrue("manifestPlaceholders[\"testerDoor\"] = door" in values)
    }

    @Test
    fun `the preview's lock code goes to the preview, the beta type and the unit tests, nowhere else`() {
        val sourceSets = build.substringAfter("    sourceSets {").substringBefore("\n    }\n")
        val given = { name: String -> sourceSets.substringAfter("getByName(\"$name\") {").substringBefore("\n        }\n") }
        assertEquals(
            listOf("java.srcDir(\"src/preview/jvm\")", "res.srcDir(\"src/preview/res-lock\")"),
            given("preview").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") },
        )
        assertEquals(
            listOf(
                "java.srcDir(\"src/preview/java\")", "java.srcDir(\"src/preview/jvm\")", "res.srcDir(\"src/preview/res-lock\")",
                "assets.srcDir(\"src/preview/assets\")", "manifest.srcFile(\"src/preview/AndroidManifest.xml\")",
            ),
            given("beta").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") },
        )
        // The beta type's own unit tests need no copy of the plain code: its main has it. The others' do.
        assertTrue("for (tests in listOf(\"testDebug\", \"testRelease\", \"testUserdebug\"))" in sourceSets)
        // No other source set is given any of src/preview: the two above, and the one line that gives the plain code to
        // the three unit-test source sets, are every mention of it.
        assertEquals(
            listOf(
                "src/preview/jvm", "src/preview/res-lock",
                "src/preview/java", "src/preview/jvm", "src/preview/res-lock", "src/preview/assets", "src/preview/AndroidManifest.xml",
                "src/preview/jvm",
            ),
            Regex("(?:srcDir|srcFile)\\(\"(src/preview[^\"]*)\"\\)").findAll(build).map { it.groupValues[1] }.toList(),
        )
        // userdebug, release without minify, takes release's stand-in.
        assertTrue("java.srcDir(\"src/release/java\")" in sourceSets.substringAfter("getByName(\"userdebug\")").substringBefore("}"))
    }

    @Test
    fun `main, release, debug and the beta type's own files hold none of the gate's code or words`() {
        val gate = listOf(
            "TesterGate", "TesterLockSession", "TesterLockStore", "TesterDoor", "TesterLockRules", "TesterCode",
            "TesterInstall", "TesterGateScreen", "TesterMemory", "DoorAnswer", "GateMessage", "tester_lock_",
            "intertune-testbuild", "TESTER_LOCK", "TESTER_DOOR",
        )
        // The beta type's own folder is the Tester app's name and icon: the gate comes to it from src/preview.
        for (sourceSet in listOf("main", "release", "debug", "beta")) {
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
            // Two more questions that every build is asked and these answer with none (TestersPageBuildTest): the Testers page of
            // Settings, and the tester's number for a report.
            assertTrue(sourceSet, "internal val testers: ExtraSettingsPage? = null" in standIn)
            assertTrue(sourceSet, "fun testerNumber(context: Context): Int? = null" in standIn)
            val imports = Regex("^import (.+)$", RegexOption.MULTILINE).findAll(standIn).map { it.groupValues[1] }.toSet()
            assertEquals(
                sourceSet,
                setOf(
                    "android.content.Context", "androidx.activity.ComponentActivity", "androidx.compose.runtime.Composable",
                    "com.dd3boh.outertune.ui.screens.settings.ExtraSettingsPage",
                ),
                imports,
            )
        }
        // Any other build type the project grows would have no answer to compile against. The beta type has none
        // because it takes the preview's gate itself (src/preview/java, see sourceSets).
        val types = Regex("create\\(\"(\\w+)\"\\)").findAll(build.substringAfter("buildTypes {").substringBefore("sourceSets {"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("preview", "beta", "userdebug"), types)
    }

    @Test
    fun `main asks before anything of the app is drawn or started`() {
        val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val content = activity.substringAfter("        setContent {\n")
        val firstStatement = content.lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("if (TesterLock.holds(this@MainActivity)) return@setContent", firstStatement)
        // The gate is MainActivity's alone. The other mentions of the lock in main are the two answers every build gives, the Testers
        // page of Settings and the tester's number for a report: TestersPageBuildTest names them.
        val mentions = files("main").filter { "TesterLock.holds" in it.readText() }.map { it.name }
        assertEquals(listOf("MainActivity.kt"), mentions)
    }

    @Test
    fun `the gate's words are the preview's and the Tester app's alone, in English and French, out of Weblate's files`() {
        val names = { file: File -> Regex("<string name=\"(tester_lock_\\w+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList() }
        val english = names(File("src/preview/res-lock/values/strings-tester.xml"))
        val french = names(File("src/preview/res-lock/values-fr/strings-tester.xml"))
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
    fun `the lock's file stays out of Android's backup and out of a transfer, so a restored app makes its own install number`() {
        // The file the store writes, by the name it gives its preferences.
        val store = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterLock.kt").readText()
        val name = Regex("getSharedPreferences\\(\"(\\w+)\", Context\\.MODE_PRIVATE\\)").find(store)!!.groupValues[1]
        assertEquals("tester_lock", name)
        val kept = Regex("""<exclude\s+domain="sharedpref"\s+path="$name\.xml"\s*/>""")
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("cloud backup", kept.containsMatchIn(rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")))
        assertTrue("transfer", kept.containsMatchIn(rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")))
        assertTrue("Android 11 and earlier", kept.containsMatchIn(File("src/main/res/xml/backup_rules.xml").readText()))
        // The one place the lock's file is named in main: nothing else of it is there.
        val mentions = files("main").filter { "tester_lock" in it.readText() }.map { it.name }.sorted()
        assertEquals(listOf("backup_rules.xml", "data_extraction_rules.xml"), mentions)
    }

    @Test
    fun `the preview's manifest carries the two marks publish-preview reads`() {
        val manifest = File("src/preview/AndroidManifest.xml").readText()
        assertTrue("android:name=\"intertune.testerLock\"\n            android:value=\"\${testerLock}\"" in manifest)
        assertTrue("android:name=\"intertune.testerDoor\"\n            android:value=\"\${testerDoor}\"" in manifest)
    }
}
