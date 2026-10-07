/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.playback.MusicLevels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The living background: the cover as a few patches of colour that breathe with the music. Still,
 * it has to look like the cover it came from; moving, it has to follow the kick and then calm
 * down, and stop altogether when the music does.
 */
class LivingFieldTest {

    private val red = 0xffcc2211.toInt()
    private val green = 0xff11aa33.toInt()
    private val blue = 0xff2233dd.toInt()
    private val dark = 0xff0a0a0c.toInt()

    /** A square cover of four plain quarters: red and green above, blue and dark below. */
    private fun quarters(side: Int) = IntArray(side * side) {
        val x = it % side
        val y = it / side
        when {
            y < side / 2 && x < side / 2 -> red
            y < side / 2 -> green
            x < side / 2 -> blue
            else -> dark
        }
    }

    private val g = 4

    /** What a patch shows for a part of the cover that is all [color]. */
    private fun patchOf(color: Int) = LivingField.colourful(color, LivingField.COLOUR)

    @Test
    fun `each patch is the colour of its part of the cover`() {
        val patches = LivingField.patches(quarters(100), 100, 100, g, g)
        assertEquals(g * g, patches.size)
        for (row in 0 until g) for (column in 0 until g) {
            val want = when {
                row < g / 2 && column < g / 2 -> red
                row < g / 2 -> green
                column < g / 2 -> blue
                else -> dark
            }
            assertEquals("row $row, column $column", patchOf(want), patches[row * g + column])
        }
    }

    @Test
    fun `the grid need not be square`() {
        // three across and six down, as on a phone held upright: the top half red and green, the bottom half blue and dark
        val patches = LivingField.patches(quarters(120), 120, 120, 3, 6)
        assertEquals(18, patches.size)
        assertEquals(patchOf(red), patches[0])
        assertEquals(patchOf(green), patches[2])
        assertEquals(patchOf(blue), patches[5 * 3])
        assertEquals(patchOf(dark), patches[5 * 3 + 2])
        // the middle column lies across the line between two quarters
        assertTrue(patches[1] != patchOf(red) && patches[1] != patchOf(green))
    }

    @Test
    fun `patches are about as wide as they are tall`() {
        assertEquals("a phone upright, 448 by 997 dp", 7, LivingField.along(997f, 448f))
        assertEquals("a short phone", 5, LivingField.along(640f, 360f))
        assertEquals("a square window", 3, LivingField.along(600f, 600f))
        assertEquals("a tablet on its side", 5, LivingField.along(1280f, 800f))
        assertEquals("never more than seven", 7, LivingField.along(3000f, 300f))
        assertEquals("nothing measured yet", 3, LivingField.along(0f, 0f))
    }

    @Test
    fun `more colourful, and a grey stays the grey it is`() {
        assertEquals(0xff808080.toInt(), LivingField.colourful(0xff808080.toInt(), 1.3f))
        assertEquals(0xff000000.toInt(), LivingField.colourful(0xff000000.toInt(), 1.3f))
        assertEquals(0xffffffff.toInt(), LivingField.colourful(0xffffffff.toInt(), 1.3f))
        val brown = 0xff8a5a3c.toInt()
        val more = LivingField.colourful(brown, 1.3f)
        assertTrue("red further up: ${Integer.toHexString(more)}", (more shr 16 and 0xff) > 0x8a)
        assertTrue("blue further down", (more and 0xff) < 0x3c)
        assertEquals("unchanged at 1", brown, LivingField.colourful(brown, 1f))
        assertEquals("nothing leaves the range", 0xffff0000.toInt(), LivingField.colourful(0xffff0000.toInt(), 3f))
    }

    @Test
    fun `a patch across two colours is their mix`() {
        // 8 pixels wide, so 2 to a column: black up to the third pixel, white from the fourth
        val stripes = IntArray(8 * 8) { if (it % 8 < 3) 0xff000000.toInt() else 0xffffffff.toInt() }
        val patches = LivingField.patches(stripes, 8, 8, g, g)
        assertEquals(0xff000000.toInt(), patches[0])
        assertEquals("the second column holds one black pixel and one white", 0xff7f7f7f.toInt(), patches[1])
        assertEquals(0xffffffff.toInt(), patches[2])
    }

    @Test
    fun `a cover smaller than the grid or missing still gives every patch a colour`() {
        val tiny = LivingField.patches(intArrayOf(red, green, blue, dark), 2, 2, g, g)
        assertEquals(patchOf(red), tiny[0])
        assertEquals(patchOf(dark), tiny[g * g - 1])
        assertTrue(LivingField.patches(IntArray(0), 0, 0, g, g).all { it == LivingField.GREY })
        assertTrue("fewer pixels than it says", LivingField.patches(IntArray(10), 100, 100, g, g).all { it == LivingField.GREY })
    }

    @Test
    fun `the glow takes the cover's strongest colour`() {
        val patches = LivingField.patches(quarters(100), 100, 100, g, g)
        val strongest = LivingField.strongest(patches)
        assertTrue("one of the colours, not the dark quarter", strongest in listOf(red, blue, green).map(::patchOf))
        // a dark cover with one lit corner: the lit corner
        val moody = IntArray(16) { dark }.also { it[5] = 0xff803010.toInt() }
        assertEquals(0xff803010.toInt(), LivingField.strongest(moody))
        // no colour at all: the lightest grey
        val greys = IntArray(16) { 0xff202020.toInt() }.also { it[9] = 0xffb0b0b0.toInt() }
        assertEquals(0xffb0b0b0.toInt(), LivingField.strongest(greys))
    }

    @Test
    fun `light is turned up and down without a channel running over`() {
        assertEquals(0xff804020.toInt(), LivingField.lit(0xff804020.toInt(), 1f))
        assertEquals(0xff402010.toInt(), LivingField.lit(0xff804020.toInt(), 0.5f))
        assertEquals(0xffff8040.toInt(), LivingField.lit(0xff804020.toInt(), 2f))
        assertEquals("red is full, the others still rise", 0xffffc060.toInt(), LivingField.lit(0xff804020.toInt(), 3f))
        assertEquals(0xff000000.toInt(), LivingField.lit(0xff804020.toInt(), 0f))
    }

    @Test
    fun `one colour turns into another channel by channel`() {
        assertEquals(red, LivingField.between(red, blue, 0f))
        assertEquals(blue, LivingField.between(red, blue, 1f))
        assertEquals(0xff808080.toInt(), LivingField.between(0xff000000.toInt(), 0xffffffff.toInt(), 0.5f))
        assertEquals(blue, LivingField.between(red, blue, 7f))
    }

    @Test
    fun `the top and the bottom of the screen follow the bass, the middle the voice and the top of the spectrum`() {
        fun bands(rows: Int) = (0 until rows).map { LivingField.bandOfRow(it, rows) }
        val (b, m, h) = Triple(MusicLevels.BASS, MusicLevels.MID, MusicLevels.HIGH)
        assertEquals("a phone upright", listOf(b, b, m, h, m, b, b), bands(7))
        assertEquals(listOf(b, m, h, h, m, b), bands(6))
        assertEquals(listOf(b, m, h, m, b), bands(5))
        assertEquals(listOf(b, m, m, b), bands(4))
        assertEquals("the strip in Settings, and a phone on its side", listOf(b, m, b), bands(3))
        assertEquals(listOf(b, b), bands(2))
        assertEquals(listOf(b), bands(1))
    }

    // The cover's main colours, as the other way of colouring it

    private fun near(a: Int, b: Int, within: Int = 40): Boolean =
        abs((a shr 16 and 0xff) - (b shr 16 and 0xff)) <= within &&
            abs((a shr 8 and 0xff) - (b shr 8 and 0xff)) <= within &&
            abs((a and 0xff) - (b and 0xff)) <= within

    /** Where on the colour wheel a colour is, in degrees. */
    private fun hue(color: Int): Float {
        val r = color shr 16 and 0xff
        val g = color shr 8 and 0xff
        val b = color and 0xff
        val most = maxOf(r, g, b)
        val chroma = most - minOf(r, g, b)
        if (chroma == 0) return Float.NaN
        val sixth = when (most) {
            r -> ((g - b).toFloat() / chroma).let { if (it < 0) it + 6 else it }
            g -> (b - r).toFloat() / chroma + 2
            else -> (r - g).toFloat() / chroma + 4
        }
        return sixth * 60
    }

    private fun apart(a: Int, b: Int): Float = abs(hue(a) - hue(b)).let { minOf(it, 360 - it) }

    private fun isGrey(color: Int) = abs((color shr 16 and 0xff) - (color shr 8 and 0xff)) < 8 && abs((color shr 8 and 0xff) - (color and 0xff)) < 8

    /** A cover of plain parts: [parts] of [colors], left to right, by how many columns of 100 each takes. */
    private fun stripes(vararg parts: Pair<Int, Int>): IntArray {
        val row = parts.flatMap { (color, columns) -> List(columns) { color } }
        check(row.size == 100) { "the parts make ${row.size} columns" }
        return IntArray(100 * 100) { row[it % 100] }
    }

    @Test
    fun `the main colours are the cover's hues, the heaviest for the bass and the lighter of the rest for the top`() {
        val mains = LivingField.mainColours(quarters(100), 100, 100)
        assertEquals(3, mains.size)
        // a quarter each, and the blue is the most colourful of the three
        assertTrue("the bass ${Integer.toHexString(mains[0])} is the blue", apart(mains[MusicLevels.BASS], blue) < 12)
        assertTrue("the voice ${Integer.toHexString(mains[1])} is the red, the darker of the other two", apart(mains[MusicLevels.MID], red) < 12)
        assertTrue("the top ${Integer.toHexString(mains[2])} is the green", apart(mains[MusicLevels.HIGH], green) < 12)
        assertFalse("the dark quarter is not a main colour", mains.any { near(it, dark, 20) })
    }

    @Test
    fun `two shades of one hue are one colour, however unlike they are channel for channel`() {
        // Magenta, pink and green, as on the cover this was got wrong for: it took the magenta
        // and the pink for two colours and gave the green the row behind the cover.
        val magenta = 0xffd01fb8.toInt()
        val pink = 0xffff7fe0.toInt()
        val leaf = 0xff3fd43a.toInt()
        val mains = LivingField.mainColours(stripes(magenta to 40, pink to 35, leaf to 25), 100, 100)
        assertTrue("the bass ${Integer.toHexString(mains[0])} is the magenta and the pink together", apart(mains[MusicLevels.BASS], magenta) < 20)
        val others = listOf(mains[MusicLevels.MID], mains[MusicLevels.HIGH])
        assertTrue("one of the others is the green: ${others.map(Integer::toHexString)}", others.any { apart(it, leaf) < 15 })
        assertFalse("and neither is the pink: ${others.map(Integer::toHexString)}", others.any { near(it, pink, 40) || near(it, magenta, 40) })
        for (i in 0 until 3) for (j in i + 1 until 3) {
            assertFalse("${Integer.toHexString(mains[i])} and ${Integer.toHexString(mains[j])} are told apart", near(mains[i], mains[j], 40))
        }
    }

    @Test
    fun `of the cover's colours the two furthest from the bass's and from each other are taken`() {
        // Magenta, a rose close to it, green and white, by how much of the cover each has. The
        // rose is the second most of it, and is the one left out: it is the magenta's neighbour.
        val magenta = 0xffd01fb8.toInt()
        val rose = 0xffe64b78.toInt()
        val leaf = 0xff3fd43a.toInt()
        val white = 0xfff2f2f2.toInt()
        val mains = LivingField.mainColours(stripes(magenta to 40, rose to 25, leaf to 20, white to 15), 100, 100)
        assertTrue("the bass ${Integer.toHexString(mains[0])}", apart(mains[MusicLevels.BASS], magenta) < 20)
        assertTrue("the voice ${Integer.toHexString(mains[1])} is the green", apart(mains[MusicLevels.MID], leaf) < 15)
        assertTrue("the top ${Integer.toHexString(mains[2])} is the white", isGrey(mains[MusicLevels.HIGH]) && LivingField.luma(mains[MusicLevels.HIGH]) > 200)
    }

    @Test
    fun `a speck of another colour is not a main colour`() {
        val mains = LivingField.mainColours(stripes(blue to 99, red to 1), 100, 100)
        assertTrue(apart(mains[MusicLevels.BASS], blue) < 12)
        assertFalse("the red speck is none of them: ${mains.map(Integer::toHexString)}", mains.any { apart(it, red) < 20 })
    }

    @Test
    fun `the cover's white is one of its colours, and the lightest goes to the top of the spectrum`() {
        val white = 0xfff4f4f0.toInt()
        val mains = LivingField.mainColours(stripes(red to 50, green to 30, white to 20), 100, 100)
        assertTrue(apart(mains[MusicLevels.BASS], red) < 12)
        assertTrue(apart(mains[MusicLevels.MID], green) < 12)
        assertTrue("the top ${Integer.toHexString(mains[2])} is the white, a little short of full", isGrey(mains[MusicLevels.HIGH]) && LivingField.luma(mains[MusicLevels.HIGH]) in 200..235)
    }

    @Test
    fun `a cover of one colour gives that colour and two neighbours of it`() {
        val plain = IntArray(64 * 64) { red }
        val mains = LivingField.mainColours(plain, 64, 64)
        assertTrue(apart(mains[MusicLevels.BASS], red) < 8)
        assertEquals("three different ones, or the screen would be one flat colour", 3, mains.toSet().size)
        assertTrue("the voice is a little way round the wheel: ${apart(mains[MusicLevels.MID], red)}", apart(mains[MusicLevels.MID], red) in 25f..50f)
        assertTrue("the top is lighter than both", LivingField.luma(mains[MusicLevels.HIGH]) > LivingField.luma(mains[MusicLevels.BASS]) + 40)
        // still the red's family: redder than they are green or blue, all three
        for (main in mains) assertTrue(Integer.toHexString(main), (main shr 16 and 0xff) > (main shr 8 and 0xff) && (main shr 16 and 0xff) > (main and 0xff))
    }

    @Test
    fun `a dark colour is given enough light to be seen moving`() {
        val navy = 0xff0c1440.toInt()
        val mains = LivingField.mainColours(IntArray(64 * 64) { navy }, 64, 64)
        val bass = mains[MusicLevels.BASS]
        assertTrue("still a blue: ${Integer.toHexString(bass)}", apart(bass, navy) < 12)
        assertTrue("and no longer nearly black: ${Integer.toHexString(bass)}", (bass and 0xff) >= 110)
        assertTrue("nor more of a blue than the navy is, by much", LivingField.colourfulness(bass) <= LivingField.colourfulness(navy) * 1.75f)
    }

    @Test
    fun `a grey cover with an accent keeps its grey for the bass and the accent for one of the others`() {
        val black = 0xff050505.toInt()
        val mains = LivingField.mainColours(stripes(black to 92, red to 8), 100, 100)
        assertTrue("the bass ${Integer.toHexString(mains[0])} is a grey, darker than the stand-in one", isGrey(mains[MusicLevels.BASS]) && LivingField.luma(mains[MusicLevels.BASS]) in 80..120)
        assertTrue("the red is there: ${mains.map(Integer::toHexString)}", listOf(mains[MusicLevels.MID], mains[MusicLevels.HIGH]).any { !isGrey(it) && apart(it, red) < 12 })
        assertEquals(3, mains.toSet().size)
    }

    @Test
    fun `a grey cover gives greys that can be told apart, and no cover gives the stand-in grey`() {
        val greys = IntArray(64 * 64) { if (it % 64 < 32) 0xff303030.toInt() else 0xffa0a0a0.toInt() }
        val mains = LivingField.mainColours(greys, 64, 64)
        for (main in mains) assertTrue(Integer.toHexString(main), isGrey(main))
        val lights = mains.map(LivingField::luma).sorted()
        assertTrue("three greys a step apart: $lights", lights[1] - lights[0] >= 20 && lights[2] - lights[1] >= 20)
        val whiteOnBlack = IntArray(64 * 64) { if (it % 64 < 50) 0xff000000.toInt() else 0xffffffff.toInt() }
        val drawn = LivingField.mainColours(whiteOnBlack, 64, 64)
        assertEquals("black, white and something between: ${drawn.map(Integer::toHexString)}", 3, drawn.toSet().size)
        assertTrue(drawn.all(::isGrey))
        assertTrue(LivingField.mainColours(IntArray(0), 0, 0).all { it == LivingField.GREY })
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a black that leans a hair towards blue is black, and its cover has no blue`() {
        // AC/DC's Back in Black, as the app read it on 7 Oct 2026: a black cover with grey
        // lettering, and the voice's rows came out royal blue. A black of 6, 6, 20 measures as
        // colourful as a visible slate does, and what was taken for the cover's accent was then
        // lifted into the light, tint and all.
        val black = rgb(0, 0, 0)
        val lettering = rgb(95, 100, 98)
        for ((what, nearly) in listOf("6, 6, 20" to rgb(6, 6, 20), "2, 2, 8" to rgb(2, 2, 8), "12, 12, 26" to rgb(12, 12, 26))) {
            val mains = LivingField.mainColours(stripes(black to 80, nearly to 17, lettering to 3), 100, 100)
            for (main in mains) assertTrue("a black of $what gave ${mains.map(Integer::toHexString)}", isGrey(main))
        }
        val all = LivingField.mainColours(IntArray(64 * 64) { rgb(6, 6, 20) }, 64, 64)
        assertTrue("a cover of nothing else: ${all.map(Integer::toHexString)}", all.all(::isGrey))
    }

    @Test
    fun `a black cover gives black, grey and white, far enough apart to be seen`() {
        val mains = LivingField.mainColours(stripes(rgb(16, 17, 21) to 97, rgb(95, 100, 98) to 3), 100, 100)
        val (bass, voice, top) = mains.map(LivingField::luma)
        assertTrue("${mains.map(Integer::toHexString)}", mains.all(::isGrey))
        assertTrue("the bass keeps the cover's black, lit enough to be seen moving: $bass", bass in 80..110)
        assertTrue("the voice a clear step up: $voice", voice >= bass + 35)
        assertTrue("and the top is its white: $top", top >= 200 && top >= voice + 35)
    }

    @Test
    fun `a dark colour that really is one still counts, and a few stray pixels do not`() {
        val navy = rgb(12, 20, 64)
        val dark = LivingField.mainColours(stripes(rgb(0, 0, 0) to 60, navy to 40), 100, 100)
        assertTrue("a navy is a blue: ${dark.map(Integer::toHexString)}", dark.any { !isGrey(it) && apart(it, navy) < 15 })
        // half a hundredth of the cover in a vivid red: not its accent
        val stray = IntArray(100 * 100) { if (it % 200 == 0) red else rgb(10, 10, 10) }
        val mains = LivingField.mainColours(stray, 100, 100)
        assertTrue("${mains.map(Integer::toHexString)}", mains.all(::isGrey))
    }

    @Test
    fun `a main colour is not much more colourful than it is on the cover`() {
        // a slate with a faint blue cast: lifted into the light as it stood, it came out a steel blue
        val slate = rgb(40, 48, 60)
        val bass = LivingField.mainColours(IntArray(64 * 64) { slate }, 64, 64)[MusicLevels.BASS]
        assertTrue("lighter than the slate: ${Integer.toHexString(bass)}", LivingField.luma(bass) > LivingField.luma(slate) + 40)
        assertTrue("and no more than a little more colourful: ${LivingField.colourfulness(bass)} against ${LivingField.colourfulness(slate)}",
            LivingField.colourfulness(bass) <= LivingField.colourfulness(slate) * 1.75f)
        // a colour that is light already is left as colourful as it is
        val leaf = rgb(63, 212, 58)
        val kept = LivingField.mainColours(IntArray(64 * 64) { leaf }, 64, 64)[MusicLevels.BASS]
        assertTrue(LivingField.colourfulness(kept) >= LivingField.colourfulness(leaf) * 0.95f)
        assertEquals("a grey measures as no colour", 0f, LivingField.colourfulness(rgb(128, 128, 128)), 0.002f)
        assertTrue("and a pure red as a lot", LivingField.colourfulness(rgb(255, 0, 0)) in 0.24f..0.28f)
    }

    @Test
    fun `whatever the cover, there are three colours, no two the same and none see-through`() {
        fun check(what: String, pixels: IntArray, width: Int, height: Int) {
            val mains = LivingField.mainColours(pixels, width, height)
            assertEquals(what, 3, mains.size)
            assertEquals("$what: ${mains.map(Integer::toHexString)}", 3, mains.toSet().size)
            assertTrue(what, mains.all { it ushr 24 == 0xff })
        }
        check("all black", IntArray(32 * 32) { 0xff000000.toInt() }, 32, 32)
        check("all white", IntArray(32 * 32) { 0xffffffff.toInt() }, 32, 32)
        check("one pixel", intArrayOf(red), 1, 1)
        check("see-through pixels count by their colour", IntArray(32 * 32) { 0x00cc2211 }, 32, 32)
        check("a very large one is not read pixel by pixel", IntArray(1500 * 1500) { if (it % 3 == 0) red else blue }, 1500, 1500)
        val random = java.util.Random(7)
        repeat(300) { n ->
            // a few plain colours in blocks, some covers nearly grey, some of one hue
            val side = 8 + random.nextInt(24)
            val colours = IntArray(1 + random.nextInt(5)) {
                when (n % 3) {
                    0 -> random.nextInt(0x1000000)
                    1 -> (random.nextInt(256)).let { v -> (v shl 16) or ((v + random.nextInt(9) - 4).coerceIn(0, 255) shl 8) or v }
                    else -> (random.nextInt(256) shl 16) or (random.nextInt(40) shl 8) or random.nextInt(40)
                } or (0xff shl 24)
            }
            check("cover $n", IntArray(side * side) { colours[(it / side * colours.size / side + it % 3) % colours.size] }, side, side)
        }
    }

    @Test
    fun `a hue is turned round the wheel and stays as light and as colourful`() {
        assertTrue("red a third of the way round is green", apart(LivingField.turned(0xffcc2020.toInt(), 120f), 0xff20cc20.toInt()) < 2)
        assertEquals(0xff20cc20.toInt(), LivingField.turned(0xffcc2020.toInt(), 120f))
        assertEquals("and all the way round is itself", 0xffcc2020.toInt(), LivingField.turned(0xffcc2020.toInt(), 360f))
        assertEquals("backwards too", 0xff2020cc.toInt(), LivingField.turned(0xffcc2020.toInt(), -120f))
        assertEquals("a grey has no hue to turn", 0xff808080.toInt(), LivingField.turned(0xff808080.toInt(), 90f))
    }

    // With a colour to each range the ranges lie in ribbons with curved edges, not in rows

    private val shares = FloatArray(3)
    private fun sharesAt(x: Float, y: Float, time: Float = 0f, bass: Float = 0f, voice: Float = 0f, top: Float = 0f): List<Float> {
        LivingField.flowShares(x, y, time, bass, voice, top, shares)
        return shares.toList()
    }

    @Test
    fun `the top and the bottom of the picture are the bass's, the middle line the top's, and the voice lies between`() {
        for (time in listOf(0f, 3.7f, 11f, 42f)) for (x in listOf(0.05f, 0.3f, 0.5f, 0.77f, 0.95f)) {
            assertEquals("the top edge at $x, $time", listOf(1f, 0f, 0f), sharesAt(x, 0.02f, time))
            assertEquals("the bottom edge at $x, $time", listOf(1f, 0f, 0f), sharesAt(x, 0.98f, time))
            val line = LivingField.flowLine(x, time)
            assertTrue("the line stays about the middle: $line", line in 0.42f..0.64f)
            assertEquals("on the line at $x, $time", listOf(0f, 0f, 1f), sharesAt(x, line, time))
            for (above in listOf(true, false)) {
                val half = (LivingField.flowTop(x, time, 0f) + LivingField.flowReach(x, time, above, 0f, 0f, 0f)) / 2
                assertEquals("half way to the bass at $x, $time", listOf(0f, 1f, 0f), sharesAt(x, if (above) line - half else line + half, time))
            }
        }
    }

    @Test
    fun `wherever and whenever, and whatever the music does, a place is shared out whole`() {
        val random = java.util.Random(3)
        repeat(4000) {
            val got = sharesAt(random.nextFloat(), random.nextFloat(), random.nextFloat() * 500, random.nextFloat() * 3, random.nextFloat() * 3, random.nextFloat() * 3)
            assertTrue("$got", got.all { it in 0f..1f })
            assertEquals("$got", 1f, got.sum(), 0.001f)
        }
    }

    @Test
    fun `no edge is a straight line, and the two sides are not each other's mirror`() {
        val across = (0..20).map { it / 20f }
        for (time in listOf(0f, 9f, 31f)) {
            val line = across.map { LivingField.flowLine(it, time) }
            assertTrue("the top's line rises and falls across the picture: ${line.max() - line.min()}", line.max() - line.min() > 0.05f)
            val upper = across.map { LivingField.flowLine(it, time) - LivingField.flowVoice(it, time, true, 0f, 0f) }
            val lower = across.map { LivingField.flowLine(it, time) + LivingField.flowVoice(it, time, false, 0f, 0f) }
            assertTrue("the bass's edge above it curves: ${upper.max() - upper.min()}", upper.max() - upper.min() > 0.05f)
            assertTrue("and the one below", lower.max() - lower.min() > 0.05f)
            val unlike = across.maxOf { abs(LivingField.flowVoice(it, time, true, 0f, 0f) - LivingField.flowVoice(it, time, false, 0f, 0f)) }
            assertTrue("the voice reaches further on one side than the other somewhere: $unlike", unlike > 0.04f)
        }
        // so one height is one range at the left of the picture and another at the right
        val heights = (0..100).map { it / 100f }
        assertTrue(heights.any { y -> sharesAt(0.1f, y).indexOfFirst { it > 0.9f }.let { left -> left >= 0 && sharesAt(0.9f, y).indexOfFirst { it > 0.9f }.let { right -> right >= 0 && right != left } } })
    }

    @Test
    fun `the ribbons flow with the picture's time`() {
        val places = (1..9).flatMap { x -> (1..19).map { y -> x / 10f to y / 20f } }
        val moved = places.maxOf { (x, y) -> sharesAt(x, y, 0f).zip(sharesAt(x, y, 6f)).maxOf { (a, b) -> abs(a - b) } }
        assertTrue("six seconds on, somewhere has changed hands: $moved", moved > 0.5f)
        val little = places.maxOf { (x, y) -> sharesAt(x, y, 0f).zip(sharesAt(x, y, 0.1f)).maxOf { (a, b) -> abs(a - b) } }
        assertTrue("but nowhere in a tenth of a second: $little", little < 0.25f)
        val frame = places.maxOf { (x, y) -> sharesAt(x, y, 20f).zip(sharesAt(x, y, 20f + 1f / 60)).maxOf { (a, b) -> abs(a - b) } }
        assertTrue("and from one frame to the next it cannot be seen to step: $frame", frame < 0.05f)
    }

    @Test
    fun `a kick pushes the bass in, a voice pushes it back out, and cymbals thicken the top's ribbon`() {
        for (x in listOf(0.1f, 0.5f, 0.9f)) for (above in listOf(true, false)) {
            val still = LivingField.flowVoice(x, 0f, above, 0f, 0f)
            assertTrue(LivingField.flowVoice(x, 0f, above, 0f, 1f) < still - 0.05f)
            assertTrue(LivingField.flowVoice(x, 0f, above, 1f, 0f) > still + 0.05f)
            assertTrue(LivingField.flowTop(x, 0f, 1f) > LivingField.flowTop(x, 0f, 0f) + 0.03f)
        }
        // however hard the kick, the voice keeps some room between the top's ribbon and the bass
        for (x in listOf(0.1f, 0.5f, 0.9f)) {
            val line = LivingField.flowLine(x, 0f)
            val beside = LivingField.flowTop(x, 0f, 0f) + LivingField.FLOW_EDGE / 2 + 0.01f
            assertEquals(listOf(0f, 1f, 0f), sharesAt(x, line + beside, bass = 3f))
            assertEquals(listOf(0f, 1f, 0f), sharesAt(x, line - beside, bass = 3f))
        }
    }

    @Test
    fun `five patches across where the ranges lie in ribbons, and as many along as keeps them round`() {
        assertEquals("a phone upright", 11, LivingField.along(997f, 448f, LivingField.FLOW_ACROSS, LivingField.FLOW_MOST_ALONG))
        assertEquals("the strip in Settings", 11, LivingField.along(380f, 168f, LivingField.FLOW_ACROSS, LivingField.FLOW_MOST_ALONG))
        assertEquals("a square", 5, LivingField.along(600f, 600f, LivingField.FLOW_ACROSS, LivingField.FLOW_MOST_ALONG))
        assertEquals("a picture of the cover keeps its three", 7, LivingField.along(997f, 448f))
        // the top's ribbon is never thinner than a row is tall, so some patch always stands in it
        for (time in listOf(0f, 5f, 17f)) for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(2 * (LivingField.flowTop(x, time, 0f) - LivingField.FLOW_EDGE / 2) + LivingField.FLOW_EDGE >= 1f / LivingField.FLOW_MOST_ALONG)
        }
    }

    @Test
    fun `colours are mixed by their shares`() {
        val three = intArrayOf(0xff200000.toInt(), 0xff004000.toInt(), 0xff000080.toInt())
        assertEquals(0xff200000.toInt(), LivingField.mixed(three, 0, floatArrayOf(1f, 0f, 0f)))
        assertEquals(0xff102000.toInt(), LivingField.mixed(three, 0, floatArrayOf(0.5f, 0.5f, 0f)))
        assertEquals(0xff081020.toInt(), LivingField.mixed(three, 0, floatArrayOf(0.25f, 0.25f, 0.25f)))
        assertEquals("from further along", 0xff000080.toInt(), LivingField.mixed(intArrayOf(0, 0) + three, 2, floatArrayOf(0f, 0f, 1f)))
    }

    @Test
    fun `a wide picture has no row for the top of the spectrum, so the middle of the voice's row is its`() {
        val (b, m, h) = Triple(MusicLevels.BASS, MusicLevels.MID, MusicLevels.HIGH)
        fun row(row: Int, columns: Int, rows: Int) = (0 until columns).map { LivingField.bandOfCell(it, row, columns, rows) }
        assertEquals("the strip in Settings", listOf(m, m, h, h, h, m, m), row(1, 7, 3))
        assertEquals(listOf(b, b, b, b, b, b, b), row(0, 7, 3))
        assertEquals(listOf(b, b, b, b, b, b, b), row(2, 7, 3))
        assertEquals("a tablet on its side", listOf(m, m, h, m, m), row(1, 5, 3))
        assertEquals("a square has nowhere to put it", listOf(m, m, m), row(1, 3, 3))
        // upright, a patch goes by its row and nothing else
        for (r in 0 until 7) assertEquals(List(3) { LivingField.bandOfRow(r, 7) }, row(r, 3, 7))
    }

    // Moving

    private val frame = 1f / 60

    private fun LivingMotion.run(seconds: Float, bass: Float = 0f, mid: Float = 0f, high: Float = 0f, playing: Boolean = true, measured: Boolean = true) {
        val levels = floatArrayOf(bass, mid, high)
        repeat(Math.round(seconds / frame)) { step(frame, if (measured) levels else null, playing) }
    }

    @Test
    fun `a kick shows at once and lets go slowly`() {
        val motion = motion()
        motion.run(0.05f, bass = 1f)
        assertTrue("three frames into a kick: ${motion.shown[MusicLevels.BASS]}", motion.shown[MusicLevels.BASS] > 0.75f)
        motion.run(0.15f, bass = 0f)
        assertTrue("150 ms after it: ${motion.shown[MusicLevels.BASS]}", motion.shown[MusicLevels.BASS] in 0.35f..0.75f)
        motion.run(1.5f, bass = 0f)
        assertTrue("long after: ${motion.shown[MusicLevels.BASS]}", motion.shown[MusicLevels.BASS] < 0.02f)
    }

    @Test
    fun `the top lets go faster than the bass`() {
        val motion = motion()
        motion.run(0.2f, bass = 1f, high = 1f)
        motion.run(0.2f)
        assertTrue(motion.shown[MusicLevels.HIGH] < motion.shown[MusicLevels.BASS] * 0.6f)
    }

    private val columns = 3
    private val rows = 7
    private fun motion() = LivingMotion(columns, rows)
    private fun LivingMotion.patches() = colors.take(count)

    @Test
    fun `a kick is seen along the top and the bottom, and only a little in the middle`() {
        val motion = motion()
        val bottom = columns * (rows - 1)
        val top = 0
        val middle = columns * 3
        val rest = listOf(bottom, middle, top).associateWith { motion.radius(it) }
        motion.run(0.1f, bass = 1f)
        fun grew(i: Int) = motion.radius(i) / rest.getValue(i) - 1
        assertTrue("bottom ${grew(bottom)}", grew(bottom) > 0.4f)
        assertTrue("top ${grew(top)}", grew(top) > 0.4f)
        assertEquals("as much at one end as at the other", grew(bottom), grew(top), 0.1f)
        assertTrue("the middle is the voice's, it only stirs: ${grew(middle)}", grew(middle) > 0.05f && grew(middle) < grew(bottom) * 0.6f)
    }

    @Test
    fun `a voice moves the rows either side of the middle, the top of the spectrum the very middle at half`() {
        val rest = motion()
        val voice = motion().apply { run(0.25f, mid = 1f) }
        val cymbals = motion().apply { run(0.25f, high = 1f) }
        val kick = motion().apply { run(0.25f, bass = 1f) }
        val bottom = columns * (rows - 1)
        val beside = columns * 2
        val middle = columns * 3
        fun grew(m: LivingMotion, i: Int) = m.radius(i) / rest.radius(i) - 1
        assertTrue("voice ${grew(voice, beside)}", grew(voice, beside) > 0.3f)
        assertTrue("cymbals ${grew(cymbals, middle)}", grew(cymbals, middle) > 0.15f && grew(cymbals, middle) < grew(kick, bottom) * 0.6f)
        assertEquals("neither reaches the bottom row", rest.radius(bottom), voice.radius(bottom), 0.08f)
        assertEquals(rest.radius(bottom), cymbals.radius(bottom), 0.08f)
    }

    @Test
    fun `a patch on a kick is brighter than at rest, and never brighter than white`() {
        val motion = motion()
        motion.turnTo(IntArray(motion.count) { 0xff808080.toInt() })
        val bottom = columns * (rows - 1)
        val rest = motion.color(bottom) and 0xff
        motion.run(0.1f, bass = 1f)
        val lit = motion.color(bottom) and 0xff
        assertTrue("at rest $rest, on a kick $lit", lit > rest * 1.3f)
        motion.turnTo(IntArray(motion.count) { 0xfff0f0f0.toInt() })
        motion.run(2.5f)
        motion.run(0.1f, bass = 1f)
        assertEquals(0xffffffff.toInt(), motion.color(bottom))
    }

    @Test
    fun `patches stay around their places however long it plays`() {
        val motion = motion()
        repeat(40) {
            motion.run(30f, bass = if (it % 2 == 0) 1f else 0.2f, mid = 0.6f)
            for (i in 0 until motion.count) {
                val homeX = (i % columns + 0.5f) / columns
                val homeY = (i / columns + 0.5f) / rows
                // a quarter of a cell of wandering, and the lean of a full kick on top: at most half the
                // screen away from the middle, pushed out by the lean
                val lean = LivingField.LEAN * LivingField.reach(motion.strength) * 0.6f
                assertTrue("patch $i x ${motion.x(i)}", abs(motion.x(i) - homeX) < LivingField.WANDER / columns + lean)
                assertTrue("patch $i y ${motion.y(i)}", abs(motion.y(i) - homeY) < LivingField.WANDER / rows + lean)
                assertTrue("patch $i radius ${motion.radius(i)}", motion.radius(i) in 0.8f..(0.95f * (1.05f + LivingField.MOST_SWELL)))
            }
        }
    }

    // Hits and levels

    @Test
    fun `a steady loud bass is a glow, and the kick on top of it is what jumps`() {
        val motion = motion()
        motion.run(3f, bass = 0.7f)
        val glow = motion.shown[MusicLevels.BASS]
        assertTrue("held, but well short of full: $glow", glow in 0.2f..0.4f)
        motion.run(0.07f, bass = 1f)
        val kick = motion.shown[MusicLevels.BASS]
        assertTrue("the kick: $kick against $glow", kick > glow + 0.35f)
        motion.run(0.5f, bass = 0.7f)
        assertTrue("and back to the glow: ${motion.shown[MusicLevels.BASS]}", motion.shown[MusicLevels.BASS] < glow + 0.12f)
    }

    @Test
    fun `a loud note held for seconds does not hold the picture at full`() {
        val motion = motion()
        motion.run(0.2f, bass = 1f)
        assertTrue("at first it is a hit: ${motion.shown[MusicLevels.BASS]}", motion.shown[MusicLevels.BASS] > 0.9f)
        motion.run(4f, bass = 1f)
        assertEquals("then it is only loud", LivingField.LEVEL_SHARE, motion.shown[MusicLevels.BASS], 0.05f)
    }

    @Test
    fun `out of silence a kick is a kick`() {
        val motion = motion()
        repeat(6) {
            motion.run(0.42f)
            motion.run(0.08f, bass = 1f)
            assertTrue("kick $it: ${motion.shown[MusicLevels.BASS]}", motion.shown[MusicLevels.BASS] > 0.85f)
        }
    }

    // In time

    @Test
    fun `the picture is started early by what it takes to be seen`() {
        val plain = motion()
        assertEquals(0.063f, plain.lead(), 0.001f)
        val sharp = motion().apply { smoothing = 0f }
        val soft = motion().apply { smoothing = 1f }
        assertTrue("a slow rise is started earlier still: ${sharp.lead()} ${plain.lead()} ${soft.lead()}", sharp.lead() < plain.lead() && plain.lead() < soft.lead())
        assertTrue("never by more than the output holds in its low latency setting, 90 ms, or so little that it lands late",
            sharp.lead() >= 0.045f && soft.lead() <= 0.125f)
    }

    // Changing song

    @Test
    fun `a new cover is turned into slowly at first, then faster, then slowly`() {
        assertEquals(0f, LivingField.eased(0f), 0f)
        assertEquals(1f, LivingField.eased(1f), 0f)
        assertEquals(0.5f, LivingField.eased(0.5f), 0.0001f)
        assertTrue(LivingField.eased(0.1f) < 0.03f && LivingField.eased(0.9f) > 0.97f)
        assertEquals(1f, LivingField.eased(4f), 0f)

        val black = 0xff000000.toInt()
        val white = 0xffffffff.toInt()
        val motion = motion()
        motion.turnTo(IntArray(motion.count) { black })
        motion.turnTo(IntArray(motion.count) { white })
        fun shade() = motion.colors[0] and 0xff
        val whole = LivingField.COVER_TURN
        motion.run(whole * 0.1f)
        assertTrue("a tenth of the time in, hardly begun: ${shade()}", shade() in 1..12)
        motion.run(whole * 0.4f)
        assertTrue("half way through, half way there: ${shade()}", shade() in 110..145)
        motion.run(whole * 0.4f)
        assertTrue("nearly there, and slowing: ${shade()}", shade() in 243..254)
        assertFalse(motion.colors[0] == white)
        motion.run(whole * 0.15f)
        assertEquals(white, motion.colors[0])
    }

    @Test
    fun `a cover arriving while another is being turned into starts from what is on screen`() {
        val motion = motion()
        motion.turnTo(IntArray(motion.count) { red })
        motion.turnTo(IntArray(motion.count) { blue })
        motion.run(LivingField.COVER_TURN / 2)
        val onScreen = motion.colors[0]
        assertTrue(onScreen != red && onScreen != blue)
        motion.turnTo(IntArray(motion.count) { green })
        motion.step(0f, null, true)
        assertEquals("no jump", onScreen, motion.colors[0])
        motion.run(LivingField.COVER_TURN + 0.1f)
        assertEquals(green, motion.colors[0])
    }

    // How strongly

    private fun kicked(strength: Float) = motion().apply {
        this.strength = strength
        turnTo(IntArray(count) { 0xff806040.toInt() })
        run(0.3f, bass = 1f, mid = 1f, high = 1f)
    }

    @Test
    fun `the setting starts in the middle, where the picture answers half again as much as it first did`() {
        assertEquals(1.5f, LivingField.reach(LivingField.DEFAULT_STRENGTH), 0f)
        assertEquals(3f, LivingField.reach(1f), 0f)
        assertEquals(0f, LivingField.reach(0f), 0f)
        assertEquals("nothing above the top", 3f, LivingField.reach(7f), 0f)
        assertEquals("nothing below the bottom", 0f, LivingField.reach(-1f), 0f)
        assertEquals("a setting that is not a number is the middle", 1.5f, LivingField.reach(Float.NaN), 0f)
        assertEquals(LivingField.DEFAULT_STRENGTH, LivingMotion().strength, 0f)
    }

    @Test
    fun `turned right down, a kick moves nothing`() {
        val still = motion().apply { turnTo(IntArray(count) { 0xff806040.toInt() }) }
        val bottom = columns * (rows - 1)
        val rest = Triple(still.radius(bottom), still.color(bottom), still.x(bottom))
        val restGlow = still.glowStrength()
        val restAura = still.auraRadius(0)
        val off = kicked(0f)
        // the ripple and the wander still run with the music, so compared loosely
        assertEquals(rest.first, off.radius(bottom), 0.08f)
        assertEquals(rest.second, off.color(bottom))
        assertEquals(restGlow, off.glowStrength(), 0f)
        assertEquals(restAura, off.auraRadius(0), 0f)
    }

    @Test
    fun `turned up, the same kick swells, lights and pushes more`() {
        val bottom = columns * (rows - 1)
        val low = kicked(0.25f)
        val middle = kicked(0.5f)
        val high = kicked(1f)
        assertTrue(low.radius(bottom) < middle.radius(bottom) && middle.radius(bottom) < high.radius(bottom))
        assertTrue((low.color(bottom) shr 16 and 0xff) < (middle.color(bottom) shr 16 and 0xff))
        assertTrue(low.glowStrength() < middle.glowStrength() && middle.glowStrength() < high.glowStrength())
        assertTrue(low.auraRadius(0) < middle.auraRadius(0) && middle.auraRadius(0) < high.auraRadius(0))
        // a corner patch is pushed further out
        assertTrue(abs(low.x(bottom) - 0.5f) < abs(high.x(bottom) - 0.5f))
    }

    @Test
    fun `turned all the way up it still stays inside its limits`() {
        val most = kicked(1f)
        for (i in 0 until most.count) {
            assertTrue("radius ${most.radius(i)}", most.radius(i) <= LivingField.REST_RADIUS * (1.05f + LivingField.MOST_SWELL))
            assertTrue("x ${most.x(i)}", most.x(i) in -0.15f..1.15f)
            assertTrue("y ${most.y(i)}", most.y(i) in -0.15f..1.15f)
        }
        for (i in most.aura.indices) {
            assertTrue("aura radius ${most.auraRadius(i)}", most.auraRadius(i) <= LivingField.AURA_MOST)
            assertTrue("aura strength ${most.auraStrength(i)}", most.auraStrength(i) in 0f..1f)
        }
        assertTrue(most.glowStrength() <= 1f && most.glowHeight() <= 0.8f)
    }

    @Test
    fun `a beat makes a colour stronger, not paler`() {
        val orange = 0xffc06020.toInt()
        val motion = motion().apply { strength = 1f; turnTo(IntArray(count) { orange }) }
        val bottom = columns * (rows - 1)
        motion.run(0.3f, bass = 1f)
        val lit = motion.color(bottom)
        val (r, g, b) = Triple(lit shr 16 and 0xff, lit shr 8 and 0xff, lit and 0xff)
        assertTrue("brighter: $r", r > 0xc0)
        assertTrue("and still orange, with blue well under red: $r $g $b", b < r / 2 && g < r)
    }

    // How softly

    @Test
    fun `the smoothing setting starts in the middle, where times are what they are`() {
        assertEquals(1f, LivingField.ease(LivingField.DEFAULT_SMOOTHING), 0.0001f)
        assertEquals(0.25f, LivingField.ease(0f), 0.0001f)
        assertEquals(4f, LivingField.ease(1f), 0.0001f)
        assertEquals(4f, LivingField.ease(9f), 0.0001f)
        assertEquals(0.25f, LivingField.ease(-2f), 0.0001f)
        assertEquals(1f, LivingField.ease(Float.NaN), 0f)
        assertEquals(LivingField.DEFAULT_SMOOTHING, LivingMotion().smoothing, 0f)
    }

    @Test
    fun `turned down it jumps with a kick and drops it at once, turned up it swells and lingers`() {
        fun after(smoothing: Float, kick: Float, gap: Float) = motion().apply {
            this.smoothing = smoothing
            run(kick, bass = 1f)
            if (gap > 0f) run(gap)
        }.shown[MusicLevels.BASS]

        // one frame and a bit into a kick
        val sharp = after(0f, 0.035f, 0f)
        val plain = after(0.5f, 0.035f, 0f)
        val soft = after(1f, 0.035f, 0f)
        assertTrue("sharp $sharp, plain $plain, soft $soft", sharp > 0.9f && plain in 0.5f..0.9f && soft < 0.35f)

        // a kick held for a third of a second, then a fifth of a second of nothing
        val sharpLeft = after(0f, 0.3f, 0.2f)
        val plainLeft = after(0.5f, 0.3f, 0.2f)
        val softLeft = after(1f, 0.3f, 0.2f)
        assertTrue("sharp $sharpLeft, plain $plainLeft, soft $softLeft", sharpLeft < 0.1f && plainLeft in 0.35f..0.65f && softLeft > 0.7f)
    }

    @Test
    fun `the aura is smoothed with the rest`() {
        fun underCover(smoothing: Float) = motion().apply {
            this.smoothing = smoothing
            run(0.05f, bass = 1f)
        }.let { m -> m.auraShown[m.aura.indexOfFirst { it.y == 1f && it.x == 0.5f }] }
        assertTrue(underCover(0f) > underCover(0.5f) && underCover(0.5f) > underCover(1f))
    }

    // Round the cover

    @Test
    fun `the aura has a point for every patch on the rim, each on the cover's edge`() {
        val aura = LivingField.aura(columns, rows)
        assertEquals(2 * rows + 2 * columns, aura.size)
        for (point in aura) {
            assertTrue("on an edge: ${point.x}, ${point.y}", point.x == 0f || point.x == 1f || point.y == 0f || point.y == 1f)
            assertTrue(point.x in 0f..1f && point.y in 0f..1f)
            val column = point.patch % columns
            val row = point.patch / columns
            assertTrue("a rim patch", column == 0 || column == columns - 1 || row == 0 || row == rows - 1)
            // and the one beside it: the left edge takes the left column, the top edge the top row
            if (point.x == 0f) assertEquals(0, column)
            if (point.x == 1f) assertEquals(columns - 1, column)
            if (point.y == 0f) assertEquals(0, row)
            if (point.y == 1f) assertEquals(rows - 1, row)
        }
    }

    @Test
    fun `the aura follows the bass along the cover's top and bottom, and the higher ranges down its sides`() {
        for (point in LivingField.aura(columns, rows)) {
            if (point.y == 0f || point.y == 1f) assertEquals(MusicLevels.BASS, point.band)
            else assertTrue(point.band == MusicLevels.MID || point.band == MusicLevels.HIGH)
        }
        assertTrue("the top of the spectrum has the middle of each side", LivingField.aura(columns, rows).any { it.band == MusicLevels.HIGH })
    }

    @Test
    fun `a kick lands above and below the cover and runs round to the middle of its sides`() {
        val aura = LivingField.aura(columns, rows)
        val bottomMiddle = aura.indexOfFirst { it.y == 1f && it.x == 0.5f }
        val topMiddle = aura.indexOfFirst { it.y == 0f && it.x == 0.5f }
        val sideMiddle = aura.indexOfFirst { it.x == 0f && it.y == 0.5f }
        assertEquals(0f, aura[bottomMiddle].far, 0.001f)
        assertEquals(0f, aura[topMiddle].far, 0.001f)
        assertEquals(1f, aura[sideMiddle].far, 0.001f)
        assertTrue(aura.all { it.far in 0f..1f })

        val motion = motion()
        motion.run(0.05f, bass = 1f)
        assertTrue("above and below first: ${motion.auraShown[bottomMiddle]} against ${motion.auraShown[sideMiddle]}",
            motion.auraShown[bottomMiddle] > motion.auraShown[sideMiddle] + 0.25f)
        assertEquals(motion.auraShown[bottomMiddle], motion.auraShown[topMiddle], 0.001f)
        motion.run(0.25f, bass = 1f)
        assertTrue("then all the way round: ${motion.auraShown[sideMiddle]}", motion.auraShown[sideMiddle] > 0.75f)
    }

    @Test
    fun `at rest the aura is a thin halo, and on a kick it reaches out`() {
        val motion = motion().apply { coverThere = true; settle() }
        assertEquals(LivingField.AURA_REST, motion.auraRadius(0), 0f)
        assertTrue("it is there at rest: ${motion.auraStrength(0)}", motion.auraStrength(0) in 0.2f..0.4f)
        motion.run(0.4f, bass = 1f)
        assertTrue("${motion.auraRadius(0)}", motion.auraRadius(0) > LivingField.AURA_REST * 2.5f)
        assertTrue(motion.auraStrength(0) > 0.7f)
    }

    @Test
    fun `the aura goes with the cover and comes back with it`() {
        val motion = motion().apply { coverThere = true; settle() }
        assertEquals(1f, motion.auraPresence, 0f)
        motion.coverThere = false                              // the lyrics are up, there is no cover to stand round
        assertFalse("it has to be seen to go", motion.atRest())
        motion.run(1f, playing = false, measured = false)
        assertTrue("${motion.auraPresence}", motion.auraPresence < 0.01f)
        assertEquals(0f, motion.auraStrength(0), 0.01f)
        assertTrue(motion.atRest())
        motion.coverThere = true
        assertFalse(motion.atRest())
        motion.run(1f, playing = false, measured = false)
        assertTrue(motion.auraPresence > 0.99f && motion.atRest())
    }

    // A colour to each range: each moves its own colour and no other

    private val ofBass = 0xff780000.toInt()
    private val ofVoice = 0xff007800.toInt()
    private val ofTop = 0xff000078.toInt()

    /**
     * A picture in ribbons, the bass's colour all red, the voice's all green and the top's all
     * blue: a patch's red is then the bass's doing and nobody else's, and so on.
     */
    private fun apart() = LivingMotion(LivingField.FLOW_ACROSS, LivingField.FLOW_MOST_ALONG).apply {
        separate = true
        turnTo(IntArray(count) { ofBass }, glow = ofBass, mains = intArrayOf(ofBass, ofVoice, ofTop))
    }

    private fun LivingMotion.sharesOfPatch(i: Int): List<Float> =
        FloatArray(3).also { sharesAt((i % columns + 0.5f) / columns, (i / columns + 0.5f) / rows, it) }.toList()

    /** The patch that is most [band]'s. */
    private fun LivingMotion.patchOf(band: Int): Int = (0 until count).maxBy { sharesOfPatch(it)[band] }

    /** How brightly [band]'s own colour is lit at patch [i], whatever share of the patch it has. */
    private fun LivingMotion.lightOf(band: Int, i: Int): Float {
        val channel = (color(i) shr (16 - 8 * band)) and 0xff
        return channel / (sharesOfPatch(i)[band] * grain(i))
    }

    /** Where the player has the cover on a phone held upright, in parts of the screen. */
    private fun LivingMotion.coverAsInThePlayer() = apply { coverThere = true; coverAt(0.083f, 0.097f, 0.917f, 0.48f) }

    @Test
    fun `at rest a patch is its place's mix of the three colours`() {
        val motion = apart()
        val (bass, voice, top) = Triple(motion.patchOf(MusicLevels.BASS), motion.patchOf(MusicLevels.MID), motion.patchOf(MusicLevels.HIGH))
        assertEquals("a corner is all the bass's", listOf(1f, 0f, 0f), motion.sharesOfPatch(0))
        assertEquals(listOf(1f, 0f, 0f), motion.sharesOfPatch(motion.count - 1))
        assertTrue("some patch is nearly all the voice's: ${motion.sharesOfPatch(voice)}", motion.sharesOfPatch(voice)[1] > 0.9f)
        assertTrue("and one nearly all the top's: ${motion.sharesOfPatch(top)}", motion.sharesOfPatch(top)[2] > 0.9f)
        fun channels(i: Int) = motion.color(i).let { listOf(it shr 16 and 0xff, it shr 8 and 0xff, it and 0xff) }
        assertTrue("red and nothing else: ${channels(bass)}", channels(bass).let { it[0] > 60 && it[1] == 0 && it[2] == 0 })
        assertTrue("mostly green: ${channels(voice)}", channels(voice).let { it[1] > 4 * it[0] && it[1] > 4 * it[2] })
        assertTrue("mostly blue: ${channels(top)}", channels(top).let { it[2] > 4 * it[0] && it[2] > 4 * it[1] })
        // a ribbon is not one flat colour
        assertTrue((0 until motion.columns).map { motion.color(it) }.toSet().size > 1)
    }

    @Test
    fun `a kick moves what is the bass's and leaves the top's line alone`() {
        val rest = apart()
        val kick = apart().apply { run(0.1f, bass = 1f) }
        val top = rest.patchOf(MusicLevels.HIGH)
        fun grew(i: Int) = kick.radius(i) / rest.radius(i) - 1
        assertTrue("a corner swells: ${grew(0)}", grew(0) > 0.4f)
        assertTrue("so does the one opposite: ${grew(rest.count - 1)}", grew(rest.count - 1) > 0.4f)
        assertEquals("the top's own patch does not stir", 0f, grew(top), 0.03f)
        assertEquals("nor light up", rest.lightOf(MusicLevels.HIGH, top), kick.lightOf(MusicLevels.HIGH, top), 2f)
        assertTrue("the bass's colour does: ${kick.lightOf(MusicLevels.BASS, 0)} from ${rest.lightOf(MusicLevels.BASS, 0)}",
            kick.lightOf(MusicLevels.BASS, 0) > rest.lightOf(MusicLevels.BASS, 0) * 1.3f)
        // and it comes in: the patches on the edge of the voice's ribbon turn the bass's
        val before = (0 until rest.count).sumOf { rest.sharesOfPatch(it)[0].toDouble() }
        val after = (0 until kick.count).sumOf { kick.sharesOfPatch(it)[0].toDouble() }
        assertTrue("the bass has more of the picture on a kick: $after against $before", after > before + 2.0)
    }

    @Test
    fun `the bass pushes, the voice glows and the top glints`() {
        val rest = apart()
        fun heard(bass: Float = 0f, mid: Float = 0f, high: Float = 0f) = apart().apply { run(0.25f, bass, mid, high) }
        val kick = heard(bass = 1f)
        val voice = heard(mid = 1f)
        val cymbals = heard(high = 1f)
        val (ofTheBass, ofTheVoice, ofTheTop) = Triple(0, rest.patchOf(MusicLevels.MID), rest.patchOf(MusicLevels.HIGH))
        fun grew(m: LivingMotion, i: Int) = m.radius(i) / rest.radius(i) - 1
        fun lit(m: LivingMotion, band: Int, i: Int) = m.lightOf(band, i) / rest.lightOf(band, i)
        assertTrue("each swells less than the one below it: ${grew(kick, ofTheBass)}, ${grew(voice, ofTheVoice)}, ${grew(cymbals, ofTheTop)}",
            grew(kick, ofTheBass) > grew(voice, ofTheVoice) * 1.2f && grew(voice, ofTheVoice) > grew(cymbals, ofTheTop) * 1.2f)
        assertTrue("the top still moves: ${grew(cymbals, ofTheTop)}", grew(cymbals, ofTheTop) > 0.05f)
        val lights = listOf(lit(kick, MusicLevels.BASS, ofTheBass), lit(voice, MusicLevels.MID, ofTheVoice), lit(cymbals, MusicLevels.HIGH, ofTheTop))
        assertTrue("and each is lit more than the one below it: $lights", lights[2] > lights[1] && lights[1] > lights[0] && lights[0] > 1.3f)
        assertEquals("a voice does not light the bass's colour", 1f, lit(voice, MusicLevels.BASS, ofTheBass), 0.03f)
        assertEquals("nor cymbals the voice's", 1f, lit(cymbals, MusicLevels.MID, ofTheVoice), 0.06f)
    }

    @Test
    fun `a range that is quiet sits darker than the bass does, so that it is seen to come in`() {
        val quiet = apart()
        val bass = quiet.lightOf(MusicLevels.BASS, 0)
        val voice = quiet.lightOf(MusicLevels.MID, quiet.patchOf(MusicLevels.MID))
        val top = quiet.lightOf(MusicLevels.HIGH, quiet.patchOf(MusicLevels.HIGH))
        assertTrue("$bass, $voice, $top", bass > voice + 2 && voice > top + 2)
        assertTrue("not dark, only darker", top > bass * 0.8f)
    }

    @Test
    fun `a held voice is seen for as long as it is held`() {
        val kept = apart().apply { run(4f, mid = 0.8f) }
        val whole = motion().apply { run(4f, mid = 0.8f) }
        assertEquals(LivingField.OWN_LEVEL[MusicLevels.MID] * 0.8f, kept.shown[MusicLevels.MID], 0.03f)
        assertEquals(LivingField.LEVEL_SHARE * 0.8f, whole.shown[MusicLevels.MID], 0.03f)
        assertTrue(kept.shown[MusicLevels.MID] > whole.shown[MusicLevels.MID] * 1.3f)
    }

    @Test
    fun `the ribbons flow while the music plays and stand still when it has stopped`() {
        val motion = apart()
        val places = (0 until motion.count)
        fun now() = places.map { motion.sharesOfPatch(it) }
        val first = now()
        motion.run(8f, bass = 0.3f, mid = 0.3f, high = 0.3f)
        val later = now()
        assertTrue("eight seconds of music on, patches have changed hands", first.zip(later).maxOf { (a, b) -> a.zip(b).maxOf { (x, y) -> abs(x - y) } } > 0.4f)
        motion.run(6f, playing = false, measured = false)
        val stopped = now()
        motion.run(3f, playing = false, measured = false)
        assertTrue("three seconds of nothing on, none has", stopped.zip(now()).all { (a, b) -> a.zip(b).all { (x, y) -> abs(x - y) < 0.001f } })
        assertTrue(motion.atRest())
    }

    @Test
    fun `the halo takes its colour and its range from the ribbons it stands on`() {
        val motion = apart().coverAsInThePlayer()
        val aura = motion.aura
        val topMiddle = aura.indexOfFirst { it.y == 0f && abs(it.x - 0.5f) < 0.11f }
        fun channels(color: Int) = listOf(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)
        // each point lights in the mix of the place it stands on
        val place = FloatArray(3)
        for (i in aura.indices) {
            motion.sharesAt(0.083f + aura[i].x * 0.834f, 0.097f + aura[i].y * 0.383f, place)
            val most = place.indices.maxBy { place[it] }
            if (place[most] > 0.8f) assertEquals("point at ${aura[i].x}, ${aura[i].y}: ${channels(motion.auraColor(i))}", most, channels(motion.auraColor(i)).let { c -> c.indices.maxBy { c[it] } })
        }
        assertEquals("the cover's top edge stands in the bass", 0, channels(motion.auraColor(topMiddle)).let { c -> c.indices.maxBy { c[it] } })
        assertTrue("and somewhere down its sides or along its bottom the halo is another range's",
            aura.indices.any { i -> channels(motion.auraColor(i)).let { c -> c.indices.maxBy { c[it] } } != 0 })

        // long enough at one level, a point is taken as far as the ranges it stands on are, each for its share
        motion.run(4f, bass = 0.6f, mid = 0.2f, high = 0.9f)
        for (i in aura.indices) {
            motion.sharesAt(0.083f + aura[i].x * 0.834f, 0.097f + aura[i].y * 0.383f, place)
            val want = (0 until 3).sumOf { (place[it] * motion.shown[it] * LivingField.OWN_SHARE[it]).toDouble() }.toFloat()
            assertEquals("point at ${aura[i].x}, ${aura[i].y}", want, motion.auraShown[i], 0.06f)
        }
        val kick = apart().coverAsInThePlayer().apply { run(0.3f, bass = 1f) }
        assertTrue("a kick reaches out above the cover: ${kick.auraShown[topMiddle]}", kick.auraShown[topMiddle] > 0.7f)

        val whole = motion().coverAsInThePlayer().apply { turnTo(IntArray(count) { 0xff000000.toInt() or (it * 9) }) }
        for (i in whole.aura.indices) {
            val want = LivingField.lit(LivingField.colourful(whole.colors[whole.aura[i].patch], 1.15f), 1f)
            assertEquals("a picture of the cover: the colour of the part of the cover beside it", want, whole.auraColor(i))
        }
    }

    @Test
    fun `a cover that fills the picture or is off it still gives the halo a colour`() {
        val motion = apart().apply { coverThere = true; coverAt(-0.4f, -0.2f, 1.6f, 1.3f) }
        motion.run(0.2f, bass = 1f, mid = 1f, high = 1f)
        assertTrue(motion.auraShown.all { it.isFinite() && it in 0f..1.01f })
        for (i in motion.aura.indices) assertTrue(motion.auraColor(i) ushr 24 == 0xff)
    }

    @Test
    fun `the glow takes the bass's own colour when it is given one`() {
        val patches = IntArray(columns * rows) { 0xff202020.toInt() }.also { it[4] = 0xff10c040.toInt() }
        val given = motion().apply { turnTo(patches, glow = 0xffc01030.toInt()) }
        assertEquals(LivingField.lit(0xffc01030.toInt(), LivingField.GLOW_LIGHT), given.glow)
        val left = motion().apply { turnTo(patches) }
        assertEquals("left out, the strongest patch", LivingField.lit(0xff10c040.toInt(), LivingField.GLOW_LIGHT), left.glow)
    }

    @Test
    fun `the three colours turn into the next cover's as the rest does`() {
        val motion = apart()
        val next = intArrayOf(0xff004080.toInt(), 0xff808000.toInt(), 0xffe0e0e0.toInt())
        motion.turnTo(IntArray(motion.count) { next[0] }, glow = next[0], mains = next)
        val at = motion.count + 2
        assertEquals("not at once", ofBass, motion.colors[at])
        motion.run(LivingField.COVER_TURN / 2)
        assertTrue("half way there: ${Integer.toHexString(motion.colors[at])}", (motion.colors[at] shr 16 and 0xff) in 0x20..0x58 && (motion.colors[at] and 0xff) in 0x28..0x60)
        motion.run(LivingField.COVER_TURN)
        assertEquals(next.toList(), motion.colors.slice(at until at + 3))
    }

    @Test
    fun `with the music stopped it comes to rest and stays there`() {
        val motion = motion()
        motion.run(3f, bass = 0.8f, mid = 0.5f, high = 0.3f)
        assertFalse(motion.atRest())
        motion.run(6f, playing = false, measured = false)
        assertTrue("pace ${motion.pace}, shown ${motion.shown.toList()}", motion.atRest())
        val x = motion.x(5)
        val y = motion.y(5)
        motion.run(20f, playing = false, measured = false)
        assertEquals(x, motion.x(5), 0.002f)
        assertEquals(y, motion.y(5), 0.002f)
    }

    @Test
    fun `music that cannot be measured still drifts, it just does not breathe`() {
        val motion = motion()
        motion.run(5f, measured = false)
        val before = motion.drift
        motion.run(5f, measured = false)
        assertTrue("drift went from $before to ${motion.drift}", motion.drift > before + 1f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), motion.shown, 0f)
        assertFalse(motion.atRest())
    }

    @Test
    fun `the first cover is there at once, the next one is turned into`() {
        val motion = motion()
        motion.turnTo(IntArray(motion.count) { red })
        assertTrue(motion.patches().all { it == red })
        motion.turnTo(IntArray(motion.count) { blue })
        assertTrue("not switched in one go", motion.patches().all { it == red })
        motion.run(0.2f)
        assertTrue("on the way: ${Integer.toHexString(motion.colors[0])}", motion.colors[0] != red && motion.colors[0] != blue)
        motion.run(4f)
        assertTrue("arrived exactly, or the frames would never stop: ${Integer.toHexString(motion.colors[0])}", motion.patches().all { it == blue })
    }

    @Test
    fun `what lies under the patches and the glow turn with the cover too`() {
        val motion = motion()
        motion.turnTo(IntArray(motion.count) { red })
        assertEquals(LivingField.lit(red, LivingField.REST_LIGHT), motion.under)
        assertEquals(LivingField.lit(red, LivingField.GLOW_LIGHT), motion.glow)
        motion.turnTo(IntArray(motion.count) { if (it == 4) green else dark })
        assertEquals("not in one go", LivingField.lit(red, LivingField.GLOW_LIGHT), motion.glow)
        motion.run(4f)
        assertEquals("the one lit patch of a dark cover", LivingField.lit(green, LivingField.GLOW_LIGHT), motion.glow)
        assertTrue(motion.atRest().not())                       // it still plays
        motion.run(6f, playing = false, measured = false)
        assertTrue(motion.atRest())
    }

    @Test
    fun `a cover with too few patches is filled up, not read past its end`() {
        val motion = motion()
        motion.turnTo(intArrayOf(red, blue))
        assertEquals(red, motion.colors[0])
        assertEquals(blue, motion.colors[1])
        assertEquals(LivingField.GREY, motion.colors[motion.count - 1])
    }

    @Test
    fun `told to be still, it is, with the new cover's colours`() {
        val motion = motion()
        motion.turnTo(IntArray(motion.count) { red })
        motion.run(2f, bass = 1f, mid = 1f)
        motion.turnTo(IntArray(motion.count) { blue })
        motion.settle()
        assertTrue(motion.atRest())
        assertTrue(motion.patches().all { it == blue })
    }

    @Test
    fun `a level that is not a number is no level`() {
        val motion = motion()
        motion.step(frame, floatArrayOf(Float.NaN, 7f, -3f), true)
        motion.run(1f, bass = 0.5f)
        assertTrue(motion.shown.all { it in 0f..1f })
        assertTrue((0 until motion.count).all { motion.radius(it).isFinite() && motion.x(it).isFinite() })
    }

    @Test
    fun `a long gap between two frames is not a leap`() {
        val one = motion().apply { step(0.1f, floatArrayOf(1f, 1f, 1f), true) }
        val other = motion().apply { step(30f, floatArrayOf(1f, 1f, 1f), true) }
        assertEquals(one.drift, other.drift, 0f)
        assertArrayEquals(one.shown, other.shown, 0f)
    }
}
