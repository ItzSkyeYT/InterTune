/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pose arithmetic, with no audio and no Android in it.
 *
 * The head frame here is the head tracker's own, which is not the usual Android one: X out the
 * right ear, Y out the nose, Z out the top of the head. So yaw is rotation about Z, and getting
 * that wrong means the soundstage responds to nodding.
 */
class HeadTrackingTest {

    private fun deg(d: Double) = (d * PI / 180.0).toFloat()

    /** A rotation of [angle] about a unit axis, as the Euler vector the sensor reports. */
    private fun rotVec(ax: Float, ay: Float, az: Float, angle: Float) =
        floatArrayOf(ax * angle, ay * angle, az * angle)

    /** Hamilton product, so two rotations can be composed into one rotation vector. */
    private fun compose(a: FloatArray, b: FloatArray): FloatArray {
        fun quat(v: FloatArray): DoubleArray {
            val t = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()
            if (t < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            val s = sin(t / 2) / t
            return doubleArrayOf(cos(t / 2), v[0] * s, v[1] * s, v[2] * s)
        }
        val (w1, x1, y1, z1) = quat(a)
        val (w2, x2, y2, z2) = quat(b)
        val w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
        val x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2
        val y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2
        val z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-12) return floatArrayOf(0f, 0f, 0f)
        val angle = 2 * atan2(n, w)
        return floatArrayOf((x / n * angle).toFloat(), (y / n * angle).toFloat(), (z / n * angle).toFloat())
    }

    private operator fun DoubleArray.component1() = this[0]
    private operator fun DoubleArray.component2() = this[1]
    private operator fun DoubleArray.component3() = this[2]
    private operator fun DoubleArray.component4() = this[3]

    private fun yaw(v: FloatArray) = HeadTracking.yawOf(v[0], v[1], v[2]) * 180f / PI.toFloat()

    @Test
    fun `a head that has not moved reports no yaw`() {
        // The common case, and the one that needs the small angle series rather than sin(t)/t.
        assertEquals(0f, yaw(floatArrayOf(0f, 0f, 0f)), 1e-4f)
        assertEquals(0f, yaw(floatArrayOf(1e-7f, -1e-7f, 1e-7f)), 1e-3f)
    }

    @Test
    fun `turning the head is yaw, and left is positive`() {
        for (d in listOf(-170.0, -90.0, -40.0, -5.0, 5.0, 40.0, 90.0, 170.0)) {
            assertEquals("$d deg", d.toFloat(), yaw(rotVec(0f, 0f, 1f, deg(d))), 1e-3f)
        }
    }

    @Test
    fun `nodding is not yaw`() {
        // Rotation about the ear axis. If this leaked into yaw the stage would swing when the
        // listener looked down at their phone.
        for (d in listOf(-80.0, -30.0, 30.0, 80.0)) {
            assertEquals("pitch $d", 0f, yaw(rotVec(1f, 0f, 0f, deg(d))), 1e-4f)
        }
    }

    @Test
    fun `tilting the head is not yaw either`() {
        for (d in listOf(-80.0, -30.0, 30.0, 80.0)) {
            assertEquals("roll $d", 0f, yaw(rotVec(0f, 1f, 0f, deg(d))), 1e-4f)
        }
    }

    @Test
    fun `yaw survives lying on your side`() {
        // Roll ninety degrees, then turn forty. The twist about the head's own up axis is still
        // forty, which is the whole reason for using the twist rather than the nose's azimuth.
        val composed = compose(rotVec(0f, 1f, 0f, deg(90.0)), rotVec(0f, 0f, 1f, deg(40.0)))
        assertEquals(40f, yaw(composed), 1e-2f)
    }

    @Test
    fun `and it does not flip when the nose points at the ceiling`() {
        // The azimuth-of-the-nose form flips by 180 degrees either side of vertical. Reachable by
        // lying on your back with the phone overhead, so not a theoretical case.
        val just_under = compose(rotVec(0f, 0f, 1f, deg(40.0)), rotVec(1f, 0f, 0f, deg(85.0)))
        val just_over = compose(rotVec(0f, 0f, 1f, deg(40.0)), rotVec(1f, 0f, 0f, deg(95.0)))
        assertTrue(
            "under ${yaw(just_under)} over ${yaw(just_over)}",
            abs(HeadTracking.wrapPi(yaw(just_under) - yaw(just_over))) < 12f,
        )
    }

    @Test
    fun `the two ways of writing the same rotation agree`() {
        // A quaternion and its negation are the same rotation. Wrapping is what makes the twist
        // form immune to which one the tracker happened to send.
        val near = rotVec(0f, 0f, 1f, deg(179.0))
        val other = rotVec(0f, 0f, -1f, deg(181.0))
        assertEquals(abs(yaw(near)), abs(yaw(other)), 1f)
    }

    @Test
    fun `wrap takes the short way round`() {
        assertEquals(deg(-10.0), HeadTracking.wrapPi(deg(350.0)), 1e-5f)
        assertEquals(deg(10.0), HeadTracking.wrapPi(deg(-350.0)), 1e-5f)
        assertEquals(deg(1.0), HeadTracking.wrapPi(deg(361.0)), 1e-5f)
    }

    @Test
    fun `stillness needs a full window before it says anything`() {
        val s = StillYaw(1_000_000_000L, deg(10.0))
        var t = 0L
        repeat(10) { s.add(t, 0f); t += 40_000_000L }
        assertFalse("400 ms in, nothing is known yet", s.isStill(t))
    }

    @Test
    fun `a head that has held still is still`() {
        val s = StillYaw(1_000_000_000L, deg(10.0))
        var t = 0L
        repeat(60) { s.add(t, 0.001f); t += 40_000_000L }
        assertTrue(s.isStill(t))
    }

    @Test
    fun `a steady turn is not still, though every step is tiny`() {
        // The reason the window is measured against its newest sample rather than each sample's
        // predecessor. Every step here is one degree, which any per-step test would wave through,
        // and the window spans twenty five of them.
        val s = StillYaw(1_000_000_000L, deg(10.0))
        var t = 0L
        var y = 0f
        repeat(60) { s.add(t, y); y += deg(1.0); t += 40_000_000L }
        assertFalse(s.isStill(t))
    }

    @Test
    fun `but a turn slower than the threshold counts as still, and that is the deal`() {
        // Under about ten degrees per window nothing can tell a deliberate slow turn from drift,
        // and drift is the thing that has to be corrected, so this direction is chosen knowingly.
        // It is why holding a turn eventually makes the soundstage follow.
        val s = StillYaw(1_000_000_000L, deg(10.0))
        var t = 0L
        var y = 0f
        repeat(60) { s.add(t, y); y += deg(0.2); t += 40_000_000L }
        assertTrue(s.isStill(t))
    }

    @Test
    fun `motion keeps it unstill for a whole window afterwards`() {
        val s = StillYaw(1_000_000_000L, deg(10.0))
        var t = 0L
        repeat(60) { s.add(t, 0f); t += 40_000_000L }
        assertTrue(s.isStill(t))

        s.add(t, deg(40.0)); t += 40_000_000L
        assertFalse("moved", s.isStill(t))
        // Still within the hysteresis window, even though everything since is identical.
        repeat(10) { s.add(t, deg(40.0)); t += 40_000_000L }
        assertFalse("too soon after moving", s.isStill(t))
    }

    @Test
    fun `a recentre cannot immediately trigger another`() {
        val s = StillYaw(1_000_000_000L, deg(10.0))
        var t = 0L
        repeat(60) { s.add(t, 0f); t += 40_000_000L }
        assertTrue(s.isStill(t))
        s.reset()
        assertFalse("reset means the window has to fill again", s.isStill(t))
    }
}
