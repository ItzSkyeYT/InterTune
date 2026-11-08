/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Living blur while a change plays as a mix: its colours go from the outgoing cover's to the
 * incoming one's as far as the change is heard (LivingMotion.blendTo), and the incoming cover,
 * turned into once the change is over, takes over from there with nothing jumping.
 */
class LivingBlendTest {

    private val red = 0xffd02020.toInt()
    private val blue = 0xff2040d0.toInt()
    private val frame = 1f / 30

    private fun LivingMotion.patches() = colors.copyOf(count)

    private fun LivingMotion.run(seconds: Float, playing: Boolean = true) = repeat(Math.round(seconds / frame)) { step(frame, null, playing) }

    @Test
    fun `the colours follow the share of the change heard`() {
        val motion = LivingMotion()
        motion.turnTo(IntArray(motion.count) { red })
        motion.blendTo(IntArray(motion.count) { blue })
        motion.run(0.1f)
        assertTrue("nothing yet", motion.patches().all { it == red })
        motion.blend = 0.5f
        motion.run(frame)
        assertTrue(motion.patches().all { it == LivingField.between(red, blue, 0.5f) })
        motion.blend = 1f
        motion.run(frame)
        assertTrue(motion.patches().all { it == blue })
        // What lies under the patches and the glow go with them.
        assertEquals(LivingField.lit(blue, LivingField.REST_LIGHT), motion.under)
    }

    @Test
    fun `paused part way, it is at rest where the music left it`() {
        val motion = LivingMotion()
        motion.turnTo(IntArray(motion.count) { red })
        motion.blendTo(IntArray(motion.count) { blue })
        motion.blend = 0.3f
        motion.run(8f, playing = false)
        assertTrue(motion.atRest())
        assertTrue(motion.patches().all { it == LivingField.between(red, blue, 0.3f) })
        // Coming back on screen, it is there at once.
        motion.blend = 0.6f
        motion.arrive()
        assertTrue(motion.patches().all { it == LivingField.between(red, blue, 0.6f) })
    }

    @Test
    fun `the incoming cover turned into after the change ends the blend, and nothing jumps`() {
        val motion = LivingMotion()
        motion.turnTo(IntArray(motion.count) { red })
        motion.blendTo(IntArray(motion.count) { blue })
        motion.blend = 1f
        motion.run(frame)
        motion.turnTo(IntArray(motion.count) { blue })
        motion.blend = 0f
        motion.run(frame)
        assertTrue("still the incoming cover's", motion.patches().all { it == blue })
        motion.run(4f)
        assertTrue(motion.patches().all { it == blue })
    }

    @Test
    fun `a change dropped part way turns on from where it was into the cover playing`() {
        val motion = LivingMotion()
        motion.turnTo(IntArray(motion.count) { red })
        motion.blendTo(IntArray(motion.count) { blue })
        motion.blend = 0.4f
        motion.run(frame)
        val at = LivingField.between(red, blue, 0.4f)
        assertTrue(motion.patches().all { it == at })
        // The screen shows the incoming song at once; its colours are turned into from the blend.
        motion.turnTo(IntArray(motion.count) { blue })
        motion.run(frame)
        assertTrue("from where it was: ${Integer.toHexString(motion.colors[0])}", motion.colors[0] != red)
        motion.run(4f)
        assertTrue(motion.patches().all { it == blue })
    }
}
