package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Blur background's blur, done once on the thumbnail in place of the GPU's on every frame:
 * it has to look like a Gaussian of the same spread, so that nobody sees the change.
 */
class CoverBlurTest {

    private fun image(w: Int, h: Int, at: (Int, Int) -> Int) = IntArray(w * h) { i -> at(i % w, i / w) }

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF

    @Test
    fun threeBoxesMatchTheSpreadAskedFor() {
        assertEquals(0, CoverBlur.boxRadius(0f))
        assertEquals(10, CoverBlur.boxRadius(10f))
        assertEquals(22, CoverBlur.boxRadius(22f))
    }

    @Test
    fun aPlainPictureStaysAsItIs() {
        val flat = image(30, 20) { _, _ -> (0xFF shl 24) or (0x40 shl 16) or (0x80 shl 8) or 0xC0 }
        assertTrue(CoverBlur.blur(flat, 30, 20, 6f, 4f).all { it == flat[0] })
    }

    @Test
    fun aPointSpreadsEvenlyAndKeepsItsLight() {
        val size = 41
        val point = image(size, size) { x, y -> if (x == 20 && y == 20) grey(255) else grey(0) }
        val out = CoverBlur.blur(point, size, size, 3f, 3f)
        val at = { x: Int, y: Int -> red(out[y * size + x]) }

        assertEquals(at(15, 20), at(25, 20))
        assertEquals(at(20, 15), at(20, 25))
        assertEquals(at(15, 20), at(20, 15))
        assertTrue(at(20, 20) > at(18, 20) && at(18, 20) > at(15, 20))
        // Rounding loses a little in each of the six passes, never much.
        val light = out.sumOf { red(it) }
        assertTrue("light kept: $light of 255", light in 200..280)
        assertTrue("alpha untouched", out.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun anEdgeSoftensAsAGaussianOfThatSpreadWould() {
        val w = 200
        val sigma = 8f
        val step = image(w, 4) { x, _ -> if (x < w / 2) grey(0) else grey(255) }
        val row = CoverBlur.blur(step, w, 4, sigma, 0f).copyOfRange(w, 2 * w).map { red(it) }

        val from = row.indexOfFirst { it >= 255 * 0.1 }
        val to = row.indexOfFirst { it >= 255 * 0.9 }
        // A Gaussian goes from 10% to 90% across 2.56 sigma.
        val across = (to - from) / sigma
        assertTrue("10 to 90% across $across sigma", across in 2.2f..3.0f)
    }
}
