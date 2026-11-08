/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Parts are held back to the debug app: the flag, the two places that read it (the debug hook's
 * `parts` command, and the editor's PartsMaker.open, which finds the debug app's maker), ONNX
 * Runtime and the :parts module as debug dependencies only, the bench and the maker in the debug
 * source set alone, and the runtime's telemetry provider taken out of the debug manifest. This
 * reads the source and the build file, as AutomixHeldBackTest does, because a flag and a dependency
 * are part of the build and a test cannot turn them.
 */
class PartsHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names the flag is not taken for a read of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `Parts are on in debug builds only, as AutoMix is`() {
        val unreleased = code(File(sources, "constants/Unreleased.kt").readText())
        assertTrue(Regex("""val AUTOMIX_PARTS = BuildConfig\.DEBUG && BuildConfig\.BUILD_TYPE == "debug"\s*$""", RegexOption.MULTILINE).containsMatchIn(unreleased))
    }

    @Test
    fun `the flag is read by the debug hook and the editor's maker alone`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { Regex("""Unreleased\.AUTOMIX_PARTS\b""").containsMatchIn(code(it.readText())) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(setOf("playback/MusicService.kt", "ui/screens/mix/PartsMaker.kt"), readers)
        val service = code(File(sources, "playback/MusicService.kt").readText())
        assertTrue("\"parts\" -> if (Unreleased.AUTOMIX_PARTS) partsBench(words.drop(1))" in service.lines().map { it.trim() })
        // The hook itself answers in the debug build only.
        assertTrue(service.contains("if (BuildConfig.BUILD_TYPE == \"debug\" && Unreleased.AUTOMIX_EDITOR) intent?.getStringExtra(\"mixtrial\")?.let { mixTrial(it) }"))
        // The editor's maker: nothing at all where the flag is off, before any name is looked up.
        val maker = code(File(sources, "ui/screens/mix/PartsMaker.kt").readText())
        val open = maker.substring(maker.indexOf("fun open(context: Context): PartsMaker?"))
        assertTrue(open.indexOf("if (!Unreleased.AUTOMIX_PARTS) return null") in 0 until open.indexOf("Class.forName"))
    }

    @Test
    fun `ONNX Runtime and the parts module are debug dependencies and nothing more`() {
        val build = code(File("build.gradle.kts").readText())
        val lines = build.lines().map { it.trim() }.filter { "onnxruntime" in it || "\":parts\"" in it }
        assertEquals(listOf("debugImplementation(project(\":parts\"))", "debugImplementation(libs.onnxruntime.android)"), lines.filter { "Implementation" in it || "implementation" in it })
        // Arm64 phones only: no other ABI of the runtime in an APK.
        assertTrue(build.contains("excludes += listOf(\"lib/armeabi-v7a/libonnxruntime*.so\", \"lib/x86/libonnxruntime*.so\", \"lib/x86_64/libonnxruntime*.so\")"))
    }

    @Test
    fun `the bench and the runtime are in the debug source set alone`() {
        assertTrue(File("src/debug/java/com/dd3boh/outertune/playback/parts/PartsBench.kt").isFile)
        assertTrue(File("src/debug/java/com/dd3boh/outertune/playback/parts/PartsOnPhone.kt").isFile)
        for (set in listOf("main", "preview", "release")) {
            val found = File("src/$set").walkTopDown()
                .filter { it.extension == "kt" }
                .filter { Regex("""import (ai\.onnxruntime|com\.dd3boh\.outertune\.parts)\.""").containsMatchIn(it.readText()) }
                .toList()
            assertEquals("$set uses the runtime or :parts: $found", emptyList<File>(), found)
        }
    }

    @Test
    fun `the hook finds the bench by the name and the method it asks for`() {
        val service = code(File(sources, "playback/MusicService.kt").readText())
        val name = Regex("""Class\.forName\("([^"]+)"\)""").findAll(service).map { it.groupValues[1] }.single { it.endsWith(".PartsBench") }
        // Only a debug build's tests have the debug source set; a release's skip this.
        val bench = runCatching { Class.forName(name) }.getOrNull()
        assumeTrue("$name is not in this build", bench != null)
        val start = bench!!.getMethod("start", android.app.Service::class.java, List::class.java)
        assertTrue("start is not static", Modifier.isStatic(start.modifiers))
    }

    @Test
    fun `the editor finds the debug app's maker by the name and the method it asks for`() {
        val maker = code(File(sources, "ui/screens/mix/PartsMaker.kt").readText())
        val name = Regex("""const val DEBUG_MAKER = "([^"]+)"""").find(maker)!!.groupValues[1]
        assertTrue("Class.forName(DEBUG_MAKER).getMethod(\"open\", Context::class.java).invoke(null, context) as PartsMaker?" in maker)
        val source = File("src/debug/java/" + name.replace('.', '/') + ".kt")
        assertTrue("$source", source.isFile)
        // The model where the notes say to put it, and one in the app at a time.
        val text = code(source.readText())
        assertTrue("const val MODEL = \"mix/models/scnet-int8.ort\"" in text)
        assertTrue("File(context.filesDir, MODEL)" in text)
        assertTrue("private val one = Semaphore(1)" in text)
        // Only a debug build's tests have the debug source set; a release's skip this.
        val found = runCatching { Class.forName(name) }.getOrNull()
        assumeTrue("$name is not in this build", found != null)
        val open = found!!.getMethod("open", android.content.Context::class.java)
        assertTrue("open is not static", Modifier.isStatic(open.modifiers))
        assertTrue(com.dd3boh.outertune.ui.screens.mix.PartsMaker::class.java.isAssignableFrom(open.returnType))
    }

    @Test
    fun `the runtime's telemetry provider is taken out of the debug app`() {
        val manifest = code(File("src/debug/AndroidManifest.xml").readText())
        val provider = Regex("""<provider\s[^>]*android:name="ai\.onnxruntime\.TelemetryInitializer"[^>]*/>""", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
        assertTrue("no removal of ONNX Runtime's TelemetryInitializer", provider != null)
        assertTrue(provider!!, "tools:node=\"remove\"" in provider)
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("onnxruntime"))
    }
}
