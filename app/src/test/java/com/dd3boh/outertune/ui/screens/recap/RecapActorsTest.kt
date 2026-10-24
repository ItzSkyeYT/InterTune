/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The paper shapes on the move: a move goes a little past its place and ends exactly on it, a
 * shape never spins round the long way, and one sent away shrinks where it is.
 */
class RecapActorsTest {
    @Test
    fun `a shape on the move never turns more than 200 degrees at once`() {
        val a = Actor(ActorId.B)
        val middle = Offset(540f, 1170f)
        val r = Random(3)
        var now = 0L
        a.place(Pose(Paper.BURST, c = listOf(Color.Red)), 100f, 100f, 100f, now, Move(jump = true), 2.6f, middle)
        var turn = a.r.target
        repeat(300) {
            now += 2_000
            val pose = Pose(Paper.entries[r.nextInt(Paper.entries.size)], r = r.nextInt(-180, 180).toFloat(), c = listOf(Color.Blue))
            a.place(pose, r.nextFloat() * 1080f, r.nextFloat() * 2340f, 60f + r.nextFloat() * 400f, now, Move(), 2.6f, middle)
            assertTrue("turned from $turn to ${a.r.target}", abs(a.r.target - turn) <= 200.001f)
            turn = a.r.target
        }
    }

    @Test
    fun `a move goes a little past its place and ends exactly on it`() {
        val t = Track(0f)
        t.go(100f, now = 1_000, delay = 0, ms = 1_100, curve = RecapMotion.Settle)
        assertEquals(0f, t.at(1_000), 1e-3f)
        assertTrue((1_000..2_100).any { t.at(it.toLong()) > 104f })
        assertEquals(100f, t.at(2_100), 1e-3f)
        assertEquals(100f, t.at(9_999), 1e-3f)
        // Sent somewhere else on the way, it sets off from where it is.
        val mid = t.at(1_300)
        t.go(0f, now = 1_300, delay = 45, ms = 1_100, curve = RecapMotion.Settle)
        assertEquals(mid, t.at(1_320), 1e-3f)
    }

    @Test
    fun `a hidden shape shrinks away where it is`() {
        val a = Actor(ActorId.D)
        val middle = Offset(540f, 1170f)
        a.place(Pose(Paper.CIRCLE, s = 40f, c = listOf(Color.Yellow)), 300f, 400f, 104f, 0, Move(jump = true), 2.6f, middle)
        a.place(Pose.HIDE, Float.NaN, Float.NaN, Float.NaN, 1_000, Move(), 2.6f, middle)
        assertEquals(300f, a.x.target, 1e-3f)
        assertEquals(400f, a.y.target, 1e-3f)
        assertEquals(0f, a.o.target, 1e-3f)
        assertEquals(104f * 0.2f, a.s.target, 1e-3f)
    }
}
