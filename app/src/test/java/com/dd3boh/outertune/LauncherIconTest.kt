/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * The Preview and Debug builds wear site tape on their launcher icon, and the release never does.
 *
 * The three install side by side under one drawing, and only the name under it told them apart.
 * Each of the two has its own adaptive icon now, with layers of its own, so that nothing a release
 * is built from has to change, and the logo the app draws inside itself stays clean in every build.
 */
class LauncherIconTest {

    private val icons = listOf("ic_launcher.xml", "ic_launcher_round.xml")
    private val taped = listOf("preview", "debug")

    private fun layers(icon: File): Map<String, String> =
        Regex("""<(background|foreground|monochrome) android:drawable="@(?:color|drawable)/([^"]+)"""")
            .findAll(icon.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `the release icon is the one it was`() {
        for (name in icons) {
            val layers = layers(File("src/main/res/mipmap-anydpi-v26/$name"))
            assertEquals(name, "ic_launcher_background", layers["background"])
            assertEquals(name, "launcher_foreground", layers["foreground"])
            assertEquals(name, "launcher_monochrome", layers["monochrome"])
        }
        // What a release is built from: main, and release's own additions. No tape in either.
        for (sourceSet in listOf("main", "release")) {
            val named = File("src/$sourceSet/res").walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertTrue(sourceSet, named.isNotEmpty())
            assertFalse("$sourceSet: $named", named.any { "_dev" in it })
        }
        // And release lays no icon of its own over main's.
        assertFalse(File("src/release/res/mipmap-anydpi-v26").exists())
    }

    @Test
    fun `Preview and Debug each have an icon of their own, drawn from layers of their own`() {
        for (sourceSet in taped) {
            for (name in icons) {
                val icon = File("src/$sourceSet/res/mipmap-anydpi-v26/$name")
                assertTrue("$sourceSet has no $name", icon.exists())
                val layers = layers(icon)
                assertEquals("$sourceSet $name", "launcher_foreground_dev", layers["foreground"])
                assertEquals("$sourceSet $name", "launcher_monochrome_dev", layers["monochrome"])
            }
            for (layer in listOf("launcher_foreground_dev", "launcher_monochrome_dev")) {
                assertTrue("$sourceSet has no $layer", File("src/$sourceSet/res/drawable/$layer.xml").exists())
            }
        }
        // The two are told apart from each other too: Preview keeps the release's ground, Debug is dark.
        assertEquals("ic_launcher_background", layers(File("src/preview/res/mipmap-anydpi-v26/ic_launcher.xml"))["background"])
        assertEquals("launcher_background_debug", layers(File("src/debug/res/mipmap-anydpi-v26/ic_launcher.xml"))["background"])
    }

    @Test
    fun `the tape lies where every launcher's mask shows it, and both layers carry it`() {
        for (sourceSet in taped) {
            val bands = listOf("launcher_foreground_dev", "launcher_monochrome_dev").map { layer ->
                val drawing = File("src/$sourceSet/res/drawable/$layer.xml").readText()
                // The stripes: each starts on the tape's lower edge and leans up to its upper one.
                val stripes = Regex("""M(-?\d+),(\d+) l6,0 l12,-(\d+) l-6,0 Z""").findAll(drawing).toList()
                assertTrue("$sourceSet $layer has ${stripes.size} stripes", stripes.size >= 8)
                val bottom = stripes.map { it.groupValues[2].toInt() }.toSet().single()
                val height = stripes.map { it.groupValues[3].toInt() }.toSet().single()
                // From edge to edge of the layer, so no mask and no parallax finds where it ends.
                assertTrue("$sourceSet $layer", stripes.minOf { it.groupValues[1].toInt() } <= 0)
                assertTrue("$sourceSet $layer", stripes.maxOf { it.groupValues[1].toInt() } >= 108 - 12)
                (bottom - height) to bottom
            }
            // One tape, in the same place whether the icon is in colour or themed.
            assertEquals(sourceSet, 1, bands.toSet().size)
            val (top, bottom) = bands.first()
            // Under the middle of the icon, and inside the 72dp of the 108 that a launcher shows.
            assertTrue("$sourceSet: $top", top > 54)
            assertTrue("$sourceSet: $bottom", bottom < 90)
            // A round mask is the tightest: 36dp about the middle. At the tape's lower edge it
            // still has to leave a third of the icon's width of tape to see.
            val reach = bottom - 54.0
            val shown = 2 * sqrt(36.0 * 36.0 - reach * reach)
            assertTrue("$sourceSet: only $shown of the tape's lower edge shows in a circle", shown >= 72 / 3.0)
        }
    }
}
