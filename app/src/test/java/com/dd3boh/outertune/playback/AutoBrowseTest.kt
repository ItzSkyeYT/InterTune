/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What Android Auto is handed since the rework, where a mistake would show only in a car. */
class AutoBrowseTest {

    private fun source(name: String) = File("src/main/java/com/dd3boh/outertune/playback/$name").readText()

    @Test
    fun `three tabs, so that the car's own More is the fourth`() {
        // The car puts the settings in a tab of its own, "More". With four of ours the last one went in there too.
        assertEquals(listOf(AutoBrowse.HOME, MusicService.PLAYLIST, AutoBrowse.LIBRARY), AutoBrowse.TABS)
    }

    @Test
    fun `a list can be subscribed to`() {
        // Media3's own onSubscribe asks onGetItem for the list, which knows songs only: the car
        // was refused and never heard of a change. Read from the source: a JVM test cannot run it.
        val callback = source("MediaLibrarySessionCallback.kt")
        assertTrue("MediaLibrarySessionCallback no longer answers onSubscribe itself", "override fun onSubscribe(" in callback)
    }

    private fun cut(vararg items: Pair<String?, Boolean>) =
        AutoBrowse.sections(items.toList()) { AutoBrowse.Laid(it.first, it.second) }.map { (how, run) -> Triple(how.heading, how.tile, run.size) }

    @Test
    fun `tiles under two headings are two sections`() {
        assertEquals(
            listOf(Triple("Quick picks", true, 2), Triple("Keep listening", true, 1)),
            cut("Quick picks" to true, "Quick picks" to true, "Keep listening" to true),
        )
    }

    @Test
    fun `a row and tiles under one heading are two sections, and the heading is said once`() {
        assertEquals(listOf(Triple("Jump back in", false, 1), Triple(null, true, 2)), cut("Jump back in" to false, "Jump back in" to true, "Jump back in" to true))
    }

    @Test
    fun `a list with no headings is one section`() {
        assertEquals(listOf(Triple(null, false, 3)), cut(null to false, null to false, null to false))
        assertEquals(emptyList<Triple<String?, Boolean, Int>>(), cut())
    }

    @Test
    fun `a heading that comes back is said again`() {
        assertEquals(listOf(Triple("A", true, 1), Triple(null, true, 1), Triple("A", true, 1)), cut("A" to true, null to true, "A" to true))
    }

    @Test
    fun `the app's own screens in a car are built only where the rework is on`() {
        // A car that finds the services draws the app from them, flag or no flag: a release with
        // the rework held back must not have them, and one that ships it must.
        val build = File("build.gradle.kts").readText()
        val release = build.substringAfter("        release {").substringBefore("        debug {")
        val shipped = Regex("val AUTO_REWORK = true\\b").containsMatchIn(File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText())
        assertEquals(shipped, "manifestPlaceholders[\"carScreens\"] = \"true\"" in release)
        assertTrue("the debug build no longer has the car's screens", "manifestPlaceholders[\"carScreens\"] = \"true\"" in build.substringAfter("        debug {").substringBefore("create(\"preview\")"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (service in listOf("AutoCarService", "AutoSettingsService")) {
            assertTrue("$service is always on", "android:enabled=\"\${carScreens}\"" in manifest.substringAfter(".playback.$service\"").substringBefore("</service>"))
        }
    }

    @Test
    fun `a release with the rework held back says nothing of the car's screens`() {
        // Switched off is not the same as absent: a release that still asked for the car's
        // template permission and named template screens it does not serve is something no
        // car has been seen to cope with. Held back, a release is to the car what it was.
        val shipped = Regex("val AUTO_REWORK = true\\b").containsMatchIn(File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText())
        val release = File("src/release/AndroidManifest.xml")
        val carFile = File("src/release/res/xml/automotive_app_desc.xml")
        if (shipped) {
            assertTrue("the rework ships: src/release must no longer take the car's screens out", !release.exists() && !carFile.exists())
            return
        }
        val taken = release.readText()
        for (name in listOf(
            "androidx.car.app.MEDIA_TEMPLATES", "com.dd3boh.outertune.playback.AutoCarService",
            "com.dd3boh.outertune.playback.AutoSettingsService", "androidx.car.app.minCarApiLevel",
        )) {
            val entry = taken.substringAfter("\"$name\"", "").substringBefore("/>")
            assertTrue("the release manifest does not take out $name", "tools:node=\"remove\"" in entry)
        }
        assertTrue("a release still tells the car it has template screens", "template" !in carFile.readText().substringAfter("<automotiveApp>"))
        assertTrue("media is gone from the release's car file", "<uses name=\"media\"" in carFile.readText())
        // userdebug is release without minify, and takes the same.
        val build = File("build.gradle.kts").readText()
        val userdebug = build.substringAfter("getByName(\"userdebug\")", "").substringBefore("}")
        assertTrue("userdebug does not take release's manifest", "manifest.srcFile(\"src/release/AndroidManifest.xml\")" in userdebug)
        assertTrue("userdebug does not take release's resources", "res.srcDir(\"src/release/res\")" in userdebug)
    }

    @Test
    fun `every drawing the tree names is one the car's screens can draw`() {
        // The tree names its icons by address; the screens look the name up in a list of their own.
        val named = Regex("drawableUri\\(R\\.drawable\\.(\\w+)\\)").findAll(source("MediaLibrarySessionCallback.kt")).map { it.groupValues[1] }.toSet()
        val known = Regex("\"(\\w+)\" to R\\.drawable\\.(\\w+)").findAll(source("AutoCar.kt")).associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("the tree names no drawing at all, so this proves nothing", named.isNotEmpty())
        assertEquals(emptySet<String>(), named - known.keys)
        for ((name, drawing) in known) assertEquals(name, drawing)
    }

    @Test
    fun `every tab is told when the song changes`() {
        // The car keeps the tabs it was handed and never asks again: unsaid, the row of what is
        // playing would show the song of the moment the car was plugged in.
        val service = source("MusicService.kt")
        val transition = service.substringAfter("override fun onMediaItemTransition(").substringBefore("\n    }\n")
        assertTrue(
            "onMediaItemTransition no longer tells the car's tabs",
            "AutoBrowse.TABS.forEach { mediaSession.notifyChildrenChanged(" in transition,
        )
    }
}
