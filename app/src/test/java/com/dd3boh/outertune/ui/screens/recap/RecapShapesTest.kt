/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The cut-paper shapes: all the same number of points round their middle, in the same order, so
 * that one can turn into another; turning as far as its symmetry leaves a star looking the same,
 * which is what lets a shape on the move land at a turn that looks right (RecapActorsTest).
 */
class RecapShapesTest {
    @Test
    fun `every shape is 96 points inside its box, the first straight up`() {
        for (paper in Paper.entries) {
            val pts = PaperShapes.of(paper)
            assertEquals(paper.name, 2 * PaperShapes.N, pts.size)
            for (i in 0 until PaperShapes.N) {
                assertTrue("$paper point $i", pts[2 * i] in -0.01f..100.01f && pts[2 * i + 1] in -0.01f..100.01f)
            }
            assertTrue("$paper starts above its middle", pts[1] < 50f)
        }
    }

    @Test
    fun `the points go round clockwise, a ninety-sixth of a turn apart`() {
        // Seen from the point each was worked out round: the middle, the play triangle's a little left of it, the dome's lower.
        val middles = mapOf(Paper.TRI to Offset(40f, 50f), Paper.DOME to Offset(50f, 58f))
        for (paper in Paper.entries) {
            val pts = PaperShapes.of(paper)
            val c = middles[paper] ?: Offset(50f, 50f)
            for (i in 0 until PaperShapes.N) {
                val a = atan2(pts[2 * i + 1] - c.y, pts[2 * i] - c.x)
                val want = -PI / 2 + 2 * PI * i / PaperShapes.N
                var d = (a - want) % (2 * PI)
                if (d > PI) d -= 2 * PI
                if (d < -PI) d += 2 * PI
                assertTrue("$paper point $i is at ${Math.toDegrees(a.toDouble())}", abs(d) < 1e-3)
            }
        }
    }

    @Test
    fun `turned as far as its symmetry, a shape looks the same`() {
        for (paper in Paper.entries.filter { it.symmetry in 1f..359f }) {
            val pts = PaperShapes.of(paper)
            val shift = (PaperShapes.N * paper.symmetry / 360f).toInt()
            val a = paper.symmetry * PI.toFloat() / 180f
            for (i in 0 until PaperShapes.N) {
                val x = pts[2 * i] - 50f
                val y = pts[2 * i + 1] - 50f
                val tx = 50f + x * cos(a) - y * sin(a)
                val ty = 50f + x * sin(a) + y * cos(a)
                val j = (i + shift) % PaperShapes.N
                assertTrue("$paper point $i turned ${paper.symmetry}", hypot(tx - pts[2 * j], ty - pts[2 * j + 1]) < 0.05f)
            }
        }
    }

    @Test
    fun `a change of outline starts at one shape and ends at the other`() {
        val out = FloatArray(2 * PaperShapes.N)
        val from = PaperShapes.of(Paper.BLOB)
        val to = PaperShapes.of(Paper.STAR8)
        PaperShapes.mix(from, to, 0f, out)
        assertTrue(out.contentEquals(from))
        PaperShapes.mix(from, to, 1f, out)
        for (i in out.indices) assertEquals(to[i], out[i], 1e-4f)
    }

    @Test
    fun `a star comes in to its inner corners, a circle not at all`() {
        assertEquals(1f, PaperShapes.inner(Paper.CIRCLE), 1e-3f)
        assertEquals(0.5f, PaperShapes.inner(Paper.STAR8), 0.02f)
        assertEquals(0.76f, PaperShapes.inner(Paper.BURST), 0.02f)
        for (paper in Paper.entries) assertTrue(paper.name, PaperShapes.inner(paper) > 0.2f)
    }
}
