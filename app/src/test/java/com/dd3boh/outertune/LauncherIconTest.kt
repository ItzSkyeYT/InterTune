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
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The Preview, Debug and Tester builds (build types preview, debug and beta) wear site tape on their launcher icon,
 * and the release never does.
 *
 * The four install side by side under one drawing, and only the name under it told them apart.
 * Each of the three has its own adaptive icon now, with layers of its own, so that nothing a release
 * is built from has to change, and the logo the app draws inside itself stays clean in every build.
 * The Tester's is the Preview's with a small badge, a white disc holding the icon's red T, in the top
 * right corner (icon B, the owner's choice of 31 Oct 2026): the app testers are given is told from the
 * Preview that is on our own phones by more than its name.
 */
class LauncherIconTest {

    private val icons = listOf("ic_launcher.xml", "ic_launcher_round.xml")

    /** The builds that wear tape, and the names of the two layers (colour, themed) each draws it on. */
    private val taped = mapOf(
        "preview" to ("launcher_foreground_dev" to "launcher_monochrome_dev"),
        "debug" to ("launcher_foreground_dev" to "launcher_monochrome_dev"),
        "beta" to ("launcher_foreground_tester" to "launcher_monochrome_tester"),
    )

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
    fun `Preview, Debug and Tester each have an icon of their own, drawn from layers of their own`() {
        for ((sourceSet, names) in taped) {
            for (name in icons) {
                val icon = File("src/$sourceSet/res/mipmap-anydpi-v26/$name")
                assertTrue("$sourceSet has no $name", icon.exists())
                val layers = layers(icon)
                assertEquals("$sourceSet $name", names.first, layers["foreground"])
                assertEquals("$sourceSet $name", names.second, layers["monochrome"])
            }
            for (layer in names.toList()) {
                assertTrue("$sourceSet has no $layer", File("src/$sourceSet/res/drawable/$layer.xml").exists())
            }
        }
        // The Preview and Debug are told apart from each other too: Preview keeps the release's ground, Debug is dark.
        assertEquals("ic_launcher_background", layers(File("src/preview/res/mipmap-anydpi-v26/ic_launcher.xml"))["background"])
        assertEquals("launcher_background_debug", layers(File("src/debug/res/mipmap-anydpi-v26/ic_launcher.xml"))["background"])
        // The Tester keeps the Preview's ground and is told apart by its badge (below), not by another colour.
        assertEquals("ic_launcher_background", layers(File("src/beta/res/mipmap-anydpi-v26/ic_launcher.xml"))["background"])
        // The round icon is the same icon: no launcher is shown a different drawing for its round mask.
        for (sourceSet in taped.keys) {
            assertEquals(
                sourceSet,
                File("src/$sourceSet/res/mipmap-anydpi-v26/ic_launcher.xml").readText(),
                File("src/$sourceSet/res/mipmap-anydpi-v26/ic_launcher_round.xml").readText(),
            )
        }
    }

    @Test
    fun `the tape lies where every launcher's mask shows it, and both layers carry it`() {
        for ((sourceSet, names) in taped) {
            val bands = names.toList().map { layer ->
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

    // --- the Tester's badge ----------------------------------------------------------------------------------

    private data class Circle(val cx: Double, val cy: Double, val r: Double)

    /** The circles written the way the drawables write them: M x,y a r,r 0 1,0 2r,0 a r,r 0 1,0 -2r,0 Z (wound counter-clockwise). */
    private fun circles(pathData: String): List<Circle> =
        Regex("""M(-?[\d.]+),(-?[\d.]+) a([\d.]+),([\d.]+) 0 1,0 ([\d.]+),0 a\3,\4 0 1,0 -\5,0 Z""").findAll(pathData).map {
            val (x, y, r) = it.destructured
            Circle(x.toDouble() + r.toDouble(), y.toDouble(), r.toDouble())
        }.toList()

    /** The points of a polygon written M x,y L x,y ... Z. */
    private fun polygon(pathData: String): List<Pair<Double, Double>> =
        Regex("""(-?[\d.]+),(-?[\d.]+)""").findAll(pathData).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()

    private fun fills(layer: File): List<Pair<String, String>> =
        Regex("""android:pathData="([^"]*)"\s+android:fillColor="([^"]*)"""").findAll(layer.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()

    private val testerForeground = File("src/beta/res/drawable/launcher_foreground_tester.xml")
    private val testerMonochrome = File("src/beta/res/drawable/launcher_monochrome_tester.xml")

    /** The elements a layer draws, without its comments and with the whitespace of each one collapsed. */
    private fun elements(layer: File): List<String> =
        Regex("<(?:path|group|clip-path)\\b[^>]*>").findAll(layer.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
            .map { it.value.replace(Regex("\\s+"), " ") }.toList()

    @Test
    fun `the Tester's colour layer is the Preview's drawing with a badge laid over it`() {
        val preview = elements(File("src/preview/res/drawable/launcher_foreground_dev.xml"))
        val tester = elements(testerForeground)
        assertTrue("the Preview's drawing has its tape and glyph: $preview", preview.size >= 7)
        // Today's Preview icon, the same elements in the same order, and then the three of the badge: its ring, its disc, its T.
        assertEquals(preview, tester.take(preview.size))
        assertEquals(preview.size + 3, tester.size)
    }

    @Test
    fun `the badge is a white disc with the icon's red T in the top right corner, inside every launcher's mask and big enough to read`() {
        val fills = fills(testerForeground)
        val (ringPath, ringColor) = fills[fills.size - 3]
        val (discPath, discColor) = fills[fills.size - 2]
        val (tPath, tColor) = fills[fills.size - 1]
        // White, with the red of the icon (its ground and its glyph) for the T and for the ring that cuts the disc free.
        assertEquals("#ffffff", discColor)
        assertEquals("#ed5564", tColor)
        assertEquals("#ed5564", ringColor)
        assertEquals("#ed5564", Regex("""<color name="ic_launcher_background">([^<]*)</color>""")
            .find(File("src/main/res/values/ic_launcher_background.xml").readText())!!.groupValues[1])
        val disc = circles(discPath).single()
        val ring = circles(ringPath).single()
        assertEquals(disc.cx, ring.cx, 0.0)
        assertEquals(disc.cy, ring.cy, 0.0)
        assertTrue("a ring, not a second disc", ring.r >= disc.r + 1.0)
        // In the top right corner: well right of the middle (54) and well above it.
        assertTrue("${disc.cx}", disc.cx >= 54 + 12)
        assertTrue("${disc.cy}", disc.cy <= 54 - 12)
        // Inside the 36 units about the middle that the tightest mask, a circle, shows: the disc's edge, and so the ring's too.
        val reach = hypot(disc.cx - 54, disc.cy - 54) + disc.r
        assertTrue("the badge reaches $reach from the middle of the layer", reach <= 36.0 - 1.0)
        // Big enough to read at launcher size: a fifth of the layer across, and clear of the tape under it.
        assertTrue("${disc.r * 2} across", disc.r * 2 >= 21.0)
        assertTrue(disc.cy + ring.r < 70.0)
        // The T: a bar across the top over a stem, both thick enough to stay a T and not a blob when the icon is small.
        val t = polygon(tPath)
        assertEquals(tPath, 8, t.size)
        val (x0, y0) = t[0]
        val (x1, _) = t[1]
        val (_, barBottom) = t[2]
        val (stemRight, _) = t[3]
        val (_, stemBottom) = t[4]
        val (stemLeft, _) = t[5]
        assertEquals("bar level", y0, t[1].second, 0.0)
        assertEquals("a square-cornered T: its stem's sides are plumb", stemRight, t[4].first, 0.0)
        assertEquals(stemLeft, t[6].first, 0.0)
        assertEquals((x0 + x1) / 2, disc.cx, 0.01)
        assertEquals("the stem is in the middle of the bar", (stemLeft + stemRight) / 2, disc.cx, 0.01)
        assertTrue("bar ${x1 - x0} wide", x1 - x0 >= 11.0)
        assertTrue("T ${stemBottom - y0} tall", stemBottom - y0 >= 12.0)
        assertTrue("bar ${barBottom - y0} thick", barBottom - y0 >= 3.5)
        assertTrue("stem ${stemRight - stemLeft} thick", stemRight - stemLeft >= 3.5)
        // Every corner of it well inside the disc, so the white is a rim all round it.
        for ((x, y) in t) assertTrue("($x, $y)", hypot(x - disc.cx, y - disc.cy) + 1.5 <= disc.r)
    }

    @Test
    fun `the themed layer has the same badge, a disc with the T taken out of it, cut free of the play shape`() {
        val fg = fills(testerForeground)
        val disc = circles(fg[fg.size - 2].first).single()
        val ring = circles(fg[fg.size - 3].first).single()
        val tPath = fg.last().first
        // The Preview's themed drawing, as it was: every element but its clip, then the badge's one path.
        val preview = elements(File("src/preview/res/drawable/launcher_monochrome_dev.xml"))
        val tester = elements(testerMonochrome)
        fun without(layer: List<String>) = layer.filterNot { it.startsWith("<clip-path") }
        assertEquals(without(preview), without(tester).dropLast(1))
        // The badge: the disc and the T in one path, filled even-odd, so that the T is a hole and shows the themed ground.
        val badge = tester.last()
        assertTrue(badge, "android:fillType=\"evenOdd\"" in badge && "android:fillColor=\"#000000\"" in badge)
        val data = Regex("""android:pathData="([^"]*)"""").find(badge)!!.groupValues[1]
        assertEquals(disc, circles(data).single())
        assertEquals(polygon(tPath), polygon(data.substringAfter(" Z ")))
        // The clip is the Preview's rectangle and, wound the other way (the circles above are counter-clockwise, the
        // rectangle clockwise), the ring around the badge: a round hole, so the badge does not run into the play shape.
        val clipOf = { layer: List<String> -> Regex("""<clip-path android:pathData="([^"]*)"""").find(layer.first { it.startsWith("<clip-path") })!!.groupValues[1] }
        val clip = clipOf(tester)
        val previewClip = clipOf(preview)
        assertEquals("M0,0 L108,0 L108,68.5 L0,68.5 Z", previewClip)
        assertTrue(clip, clip.startsWith("$previewClip "))
        assertEquals(ring, circles(clip).single())
    }
}
