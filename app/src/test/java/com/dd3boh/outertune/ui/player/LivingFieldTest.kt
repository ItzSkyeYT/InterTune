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
    fun `a colour stepped towards another gets there`() {
        var shown = red
        var steps = 0
        while (shown != blue) {
            val next = LivingField.nearer(shown, blue, 0.03f)
            assertTrue("step $steps moved nothing from ${Integer.toHexString(shown)}", next != shown)
            shown = next
            assertTrue("still not there after 400 steps", ++steps < 400)
        }
        // and never past it
        assertEquals(blue, LivingField.nearer(blue, blue, 0.5f))
        assertEquals(0xff0a0b0c.toInt(), LivingField.nearer(0xff090c0c.toInt(), 0xff0a0b0c.toInt(), 0.9f))
    }

    @Test
    fun `the bottom third breathes with the bass and the top row with the cymbals`() {
        fun bands(rows: Int) = (0 until rows).map { LivingField.bandOfRow(it, rows) }
        val (b, m, h) = Triple(MusicLevels.BASS, MusicLevels.MID, MusicLevels.HIGH)
        assertEquals(listOf(h, m, m, m, m, b, b), bands(7))
        assertEquals(listOf(h, m, m, b), bands(4))
        assertEquals(listOf(h, m, b), bands(3))
        assertEquals("with two rows the bass keeps one", listOf(h, b), bands(2))
        assertEquals("and with one row it is the bass", listOf(b), bands(1))
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
    fun `the bottom row swells on a kick and a row in the middle does not`() {
        val motion = motion()
        val bottom = columns * (rows - 1)
        val middle = columns * 2
        val restBottom = motion.radius(bottom)
        val restMiddle = motion.radius(middle)
        motion.run(0.1f, bass = 1f)
        assertTrue("bottom ${motion.radius(bottom)} from $restBottom", motion.radius(bottom) > restBottom * 1.25f)
        assertTrue("middle ${motion.radius(middle)} from $restMiddle", motion.radius(middle) < restMiddle * 1.15f)
    }

    @Test
    fun `the top row shows half of what the bottom row shows`() {
        val motion = motion()
        val top = motion.radius(0)
        val bottom = motion.radius(columns * (rows - 1))
        motion.run(1f, bass = 1f, high = 1f)
        val topGrew = motion.radius(0) / top - 1
        val bottomGrew = motion.radius(columns * (rows - 1)) / bottom - 1
        // both also take a little of the bass, the bottom row all of it
        assertTrue("top grew $topGrew, bottom $bottomGrew", topGrew < bottomGrew * 0.75f && topGrew > bottomGrew * 0.3f)
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
        motion.run(2f, bass = 1f)
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
                // a quarter of a cell of wandering, and the lean of a kick on top
                assertTrue("patch $i x ${motion.x(i)}", abs(motion.x(i) - homeX) < (LivingField.WANDER + 0.1f) / columns)
                assertTrue("patch $i y ${motion.y(i)}", abs(motion.y(i) - homeY) < (LivingField.WANDER + 0.2f) / rows)
                assertTrue("patch $i radius ${motion.radius(i)}", motion.radius(i) in 0.8f..1.6f)
            }
        }
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
