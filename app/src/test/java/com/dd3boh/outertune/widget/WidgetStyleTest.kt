/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import androidx.media3.common.C
import com.google.material.color.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The parts of the widget's three styles (25 Oct 2026) that are not its sizes: Cover's colour from
 * the cover, Full cover's scrims and the glass drawn ahead of time, Up next, shuffle, the order of
 * the buttons, and the new words, which wait for his yes before they go to translators.
 */
class WidgetStyleTest {

    private fun source(path: String) = File("src/main/$path").readText()

    // Cover's colour.

    private fun contrastWithWhite(colour: Int): Double {
        fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * linear(colour shr 16 and 0xFF) + 0.7152 * linear(colour shr 8 and 0xFF) + 0.0722 * linear(colour and 0xFF)
        return 1.05 / (l + 0.05)
    }

    @Test
    fun `Cover's background is the cover's own colour, deep enough for white words whatever the cover`() {
        val covers = listOf(0xFFFF69B4, 0xFFFFFF00, 0xFFFFFFFF, 0xFF00FFFF, 0xFF7B3FA0, 0xFF0F6E56, 0xFF808080, 0xFF000000, 0xFFE0647A)
            .map { it.toInt() }
        for (cover in covers) {
            val deep = WidgetColours.deep(cover)
            assertTrue("white words on ${Integer.toHexString(deep)} from ${Integer.toHexString(cover)}", contrastWithWhite(deep) >= 7.0)
            assertEquals(WidgetColours.DEEP_TONE, Hct.fromInt(deep).tone, 1.0)
        }
        // Its hue is the cover's; a cover of greys gives a grey.
        val pink = Hct.fromInt(0xFFE0647A.toInt())
        assertEquals(pink.hue, Hct.fromInt(WidgetColours.deep(0xFFE0647A.toInt())).hue, 6.0)
        assertTrue(Hct.fromInt(WidgetColours.deep(0xFF808080.toInt())).chroma < 4)
        assertEquals(WidgetColours.NEUTRAL_DEEP, WidgetColours.deep(null))
    }

    // Full cover's scrims: the drawables on the widget and the numbers the glass is drawn with.

    private data class Gradient(val angle: Int, val start: Float, val centre: Float?, val at: Float, val end: Float)

    private fun gradient(name: String): Gradient {
        val xml = source("res/drawable/$name.xml")
        fun attr(key: String) = Regex("""android:$key="([^"]+)"""").find(xml)?.groupValues?.get(1)
        fun alpha(hex: String?) = hex?.let { Integer.parseUnsignedInt(it.removePrefix("#"), 16).ushr(24) / 255f }
        val at = (attr("centerX") ?: attr("centerY"))?.toFloat() ?: 0.5f
        return Gradient(attr("angle")!!.toInt(), alpha(attr("startColor"))!!, alpha(attr("centerColor")), at, alpha(attr("endColor"))!!)
    }

    @Test
    fun `the glass is drawn as dark as the scrim the widget draws under it`() {
        val drawables = mapOf(
            WidgetScrim.BOTTOM to listOf("widget_scrim" to 90),
            WidgetScrim.START to listOf("widget_scrim_start" to 0, "widget_scrim_end" to 180),
            WidgetScrim.END to listOf("widget_scrim_panes" to 0, "widget_scrim_panes_rtl" to 180),
        )
        for ((scrim, files) in drawables) for ((name, angle) in files) {
            val g = gradient(name)
            assertEquals("$name's direction", angle, g.angle)
            assertEquals("$name at its start", scrim.from, g.start, 0.01f)
            assertEquals("$name at its end", scrim.to, g.end, 0.01f)
            if (g.centre != null) {
                assertEquals("$name in its middle", scrim.middle, g.centre!!, 0.01f)
                assertEquals("where $name's middle is", scrim.at, g.at, 0.001f)
            } else {
                assertEquals("$name is even", (scrim.from + scrim.to) / 2, scrim.middle, 0.001f)
            }
        }
        // In the widget: dark at the bottom of a poster, at the words' side of a banner, under Up next.
        assertEquals(0.85f, WidgetScrim.BOTTOM.alpha(10f, 100f, 100f, 100f, false), 0.001f)
        assertEquals(0f, WidgetScrim.BOTTOM.alpha(10f, 0f, 100f, 100f, false), 0.001f)
        assertEquals(0.60f, WidgetScrim.START.alpha(0f, 50f, 100f, 100f, false), 0.001f)
        assertEquals(0.60f, WidgetScrim.START.alpha(100f, 50f, 100f, 100f, rtl = true), 0.001f)
        assertEquals(0.80f, WidgetScrim.END.alpha(100f, 50f, 100f, 100f, false), 0.001f)
        assertEquals(0f, WidgetScrim.NONE.alpha(50f, 50f, 100f, 100f, false), 0f)
    }

    // The glass.

    @Test
    fun `a pane of glass is small, whatever the screen`() {
        for (density in listOf(1f, 2f, 2.625f, 3.5f, 4f)) for ((w, h) in listOf(33 to 33, 164 to 46, 240 to 52, 400 to 60)) {
            val (pw, ph) = WidgetGlass.pixels(w, h, density)
            assertTrue("$w x $h at $density: $pw x $ph", pw * ph <= WidgetGlass.MAX_PX)
            assertTrue(pw <= (w * WidgetGlass.MAX_DENSITY).toInt() + 1)
            assertEquals("its shape kept", w.toFloat() / h, pw.toFloat() / ph, 0.1f)
        }
    }

    @Test
    fun `the glass sees the cover where the launcher puts it`() {
        // A square cover over a wide widget is scaled to its width and cut top and bottom.
        val wide = coverCrop(320, 320, 400, 188)
        assertEquals(1.25f, wide.scale, 0.001f)
        assertEquals(0f, wide.left, 0.001f)
        assertEquals((188 - 400) / 2f, wide.top, 0.001f)
        val tall = coverCrop(320, 320, 150, 300)
        assertEquals(300 / 320f, tall.scale, 0.001f)
        assertEquals(0f, tall.top, 0.001f)
    }

    @Test
    fun `frosting blurs, keeps a white cover from being too bright for white buttons, and lightens a black one`() {
        val w = 40
        val h = 12
        val white = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        frost(white, w, h, radius = 3)
        val mean = white.map { lightness(it) }.average()
        assertTrue("white buttons on it: $mean", mean <= WidgetGlass.MAX_LIGHT + (1 - WidgetGlass.MAX_LIGHT) * WidgetGlass.TINT + 0.01)
        val black = IntArray(w * h) { 0xFF000000.toInt() }
        frost(black, w, h, radius = 3)
        assertEquals(WidgetGlass.TINT.toDouble(), black.map { lightness(it) }.average(), 0.01)
        // One bright point spreads out and is no longer a point.
        val dot = IntArray(w * h) { 0xFF000000.toInt() }
        dot[6 * w + 20] = 0xFFFFFFFF.toInt()
        boxBlur(dot, w, h, 2, horizontal = true)
        boxBlur(dot, w, h, 2, horizontal = false)
        assertTrue(lightness(dot[6 * w + 20]) < 0.1f)
        assertTrue(lightness(dot[5 * w + 19]) > 0f)
        // A flat picture stays flat.
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        boxBlur(flat, w, h, 4, horizontal = true)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
        assertEquals(0xFF808080.toInt(), darker(0xFFFFFFFF.toInt(), 0.5f))
    }

    // Up next.

    @Test
    fun `a song of Up next is found after the one playing, and never behind it or round past the end`() {
        val queue = listOf("a", "b", "c", "d", "b")
        val next = { i: Int -> if (i + 1 < queue.size) i + 1 else C.INDEX_UNSET }
        assertEquals(2, nextIndexOf("c", current = 0, count = queue.size, idAt = queue::get, next = next))
        // The first time it comes after the song playing: not the one behind, not the one before.
        assertEquals(4, nextIndexOf("b", current = 2, count = queue.size, idAt = queue::get, next = next))
        assertNull(nextIndexOf("a", current = 1, count = queue.size, idAt = queue::get, next = next))
        assertNull(nextIndexOf("z", current = 0, count = queue.size, idAt = queue::get, next = next))
        assertNull(nextIndexOf("a", current = 4, count = queue.size, idAt = queue::get, next = next))
    }

    @Test
    fun `the service writes the queue's shuffle and Up next with the song, and redraws on a shuffle`() {
        val service = source("java/com/dd3boh/outertune/playback/MusicService.kt")
        val update = service.substringAfter("private fun updateWidget(").substringBefore("override fun onDestroy()")
        assertTrue("read on the main thread with the player", Regex("""withContext\(Dispatchers\.Main\)[\s\S]*shuffled = activePlayer\.shuffleModeEnabled""").containsMatchIn(update))
        assertTrue(Regex("""upNext = activePlayer\.upNext\(WidgetLayout\.MAX_UP_NEXT\)""").containsMatchIn(update))
        val shuffle = service.substringAfter("override fun onShuffleModeEnabledChanged(").substringBefore("triggerShuffle()")
        assertTrue("the widget's shuffle button follows the player's", "updateWidget()" in shuffle)
        val actions = source("java/com/dd3boh/outertune/widget/WidgetActions.kt")
        assertTrue("shuffle as the player's own button does it", "activePlayer.toggleShuffleMode()" in actions)
        assertTrue("an Up next song plays the queue on from it", "activePlayer.playOnFrom(songId)" in actions)
    }

    // The buttons.

    private fun body(widget: String, name: String): String = widget.substringAfter("private fun $name(").substringBefore("\n}\n")

    /**
     * The same order at every size and in every style (his note on the every-size mockup): the
     * heart, previous, play, next, and shuffle where there is room, play in the middle of them.
     */
    @Test
    fun `the buttons are in the same order everywhere`() {
        val widget = source("java/com/dd3boh/outertune/widget/MusicWidget.kt")
        for (name in listOf("Controls", "SpreadControls")) {
            val code = body(widget, name)
            val order = listOf("LikeButton(", "R.drawable.skip_previous", "PlayButton(", "R.drawable.skip_next", "ShuffleButton(").map { code.indexOf(it) }
            assertTrue("$name has every button: $order", order.all { it >= 0 })
            assertEquals("$name's order", order.sorted(), order)
        }
    }

    /**
     * Glance keeps no more than ten children in a Row or a Column and drops the rest without a
     * word. The rows that could grow past it (the buttons, a shelf's row of covers) put the room
     * between their children as padding, never as a spacer between each.
     */
    @Test
    fun `rows of buttons and covers keep under Glance's ten children`() {
        val widget = source("java/com/dd3boh/outertune/widget/MusicWidget.kt")
        assertFalse("Spacer(" in body(widget, "Controls"))
        assertFalse("Spacer(" in body(widget, "ShelfCovers"))
        // A spread row has five places at most, a blank in a place the heart or shuffle leaves.
        assertEquals(2, Regex("""Spacer\(""").findAll(body(widget, "SpreadControls")).count())
        assertTrue(WidgetLayout.MAX_UP_NEXT + 1 <= 10)
        assertTrue("a shelf's row", 6 <= 10)
    }

    // The widget's own settings, and the launcher's record of it.

    @Test
    fun `the style is the first thing asked, and only what a style uses is offered under it`() {
        val screen = source("java/com/dd3boh/outertune/widget/WidgetConfigActivity.kt")
        val config = screen.substringAfter("private fun Config(").substringBefore("private val SheetTop")
        assertTrue(config.indexOf("R.string.widget_config_style") in 0 until config.indexOf("R.string.widget_config_buttons"))
        assertTrue(config.indexOf("R.string.widget_config_style") < config.indexOf("R.string.widget_config_shows"))
        assertTrue("Full cover has no background to pick", "if (s.style != WidgetStyle.FULL_COVER)" in config)
        assertTrue("a list is a Shelf's", "if (s.style == WidgetStyle.SHELF)" in config)
        assertTrue("the background shown is the style's own until one is picked", "s.backgroundShown" in config)
    }

    @Test
    fun `the widget can be made as wide as a tablet's, where Up next comes in`() {
        val info = source("res/xml/music_widget_info.xml")
        val max = Regex("""android:maxResizeWidth="(\d+)dp"""").find(info)!!.groupValues[1].toInt()
        assertTrue(max >= 2 * WidgetLayout.PANES_WIDTH_DP - 100)
    }

    // The words.

    private val held = listOf("widget_config_style", "widget_style_cover", "widget_style_full_cover", "widget_style_shelf", "widget_up_next")

    private fun line(folder: String, name: String) = File("src/main/res/$folder/strings-ot.xml").readLines().single { "name=\"$name\"" in it }

    /** The words of the styles wait for his yes (25 Oct 2026): held from translators, with their French beside them. */
    @Test
    fun `the styles' words are open to translators since his yes of 27 Oct, with their French beside them, short and without a long dash`() {
        for (name in held) {
            assertFalse("$name is held from translators", "translatable=\"false\"" in line("values", name))
            val french = Regex(""">([^<]+)</string>""").find(line("values-fr", name))?.groupValues?.get(1)
            assertTrue("$name has no French", !french.isNullOrBlank())
            for (folder in listOf("values", "values-fr")) {
                val text = Regex(""">([^<]+)</string>""").find(line(folder, name))!!.groupValues[1]
                assertFalse("$name has a long dash", '—' in text)
                assertTrue("$name is a name, not a sentence: $text", text.length <= 20)
            }
        }
    }

    @Test
    fun `the bitmaps of every widget stay within what the launcher is sent`() {
        // The sweep in WidgetLayoutTest holds every size to it; here the worst of them, a tablet's Full cover.
        val panes = WidgetLayout.plan(800, 400, WidgetSettings(style = WidgetStyle.FULL_COVER), 6, upNext = 4)
        assertTrue(WidgetArt.bytes(panes, 3.5f) <= WidgetArt.BUDGET_BYTES)
        assertTrue(abs(WidgetArt.bytes(WidgetLayout.plan(70, 70, WidgetSettings(), 6), 2.75f) - WidgetArt.NOW_PX * WidgetArt.NOW_PX * 4) == 0)
    }
}
