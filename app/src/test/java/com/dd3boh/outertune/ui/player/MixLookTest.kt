/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.graphics.shapes.Cubic
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.automix.MixGains
import com.dd3boh.outertune.playback.automix.MixOnScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The player while a change plays as a mix, as the design picked on 8 Nov 2026 has it
 * (experiments/features/mix-player/SPEC.md): every place is a share of the cover's box.
 */
class MixLookTest {

    private val e = 1e-4f

    private fun assertSpot(what: String, x: Float, y: Float, size: Float, spot: MixLook.Spot) {
        assertEquals("$what x", x, spot.x, e)
        assertEquals("$what y", y, spot.y, e)
        assertEquals("$what size", size, spot.size, e)
    }

    @Test
    fun `before the covers part, the playing cover is today's and the next one is not there`() {
        val covers = MixLook.covers(heard = 0f, gains = null, coming = 0f, going = 0f)
        assertEquals(MixLook.WHOLE, covers.outgoing)
        assertEquals(0f, covers.incoming.size, 0f)
        assertEquals(0f, covers.incoming.alpha, 0f)
    }

    @Test
    fun `apart at the change's start, the outgoing cover is lower left and the incoming one upper right, both cookies`() {
        val covers = MixLook.covers(heard = 0f, gains = null, coming = 1f, going = 0f)
        // gA = 1, gB = 0: (0.333 + 0.222) s and 0.333 s.
        assertSpot("outgoing", 0.328f, 0.693f, 0.555f, covers.outgoing)
        assertSpot("incoming", 0.666f, 0.310f, 0.333f, covers.incoming)
        assertEquals(1f, covers.outgoing.cookie, 0f)
        assertEquals(1f, covers.incoming.cookie, 0f)
        assertEquals(1f, covers.outgoing.alpha, 0f)
        assertEquals(1f, covers.incoming.alpha, 0f)
    }

    @Test
    fun `the sizes follow an equal-power pair through the change`() {
        val half = MixLook.covers(heard = 0.5f, gains = null, coming = 1f, going = 0f)
        val g = cos(PI / 4).toFloat()
        assertEquals(0.333f + 0.222f * g, half.outgoing.size, e)
        assertEquals(0.333f + 0.244f * g, half.incoming.size, e)
        val end = MixLook.covers(heard = 1f, gains = null, coming = 1f, going = 0f)
        assertEquals(0.333f, end.outgoing.size, e)
        assertEquals(0.577f, end.incoming.size, e)
        // Neither moves while the music sizes them.
        assertSpot("outgoing at the end", 0.328f, 0.693f, 0.333f, end.outgoing)
        assertSpot("incoming at the end", 0.666f, 0.310f, 0.577f, end.incoming)
    }

    @Test
    fun `with the plan's gains, the sizes follow those`() {
        // The outgoing song at full until half way, then gone; the incoming one in from half way.
        val out = FloatArray(MixGains.STEPS + 1) { if (it <= MixGains.STEPS / 2) 1f else 0f }
        val inc = FloatArray(MixGains.STEPS + 1) { if (it < MixGains.STEPS / 2) 0f else 1f }
        val gains = MixGains(out, inc)
        val early = MixLook.covers(heard = 0.25f, gains = gains, coming = 1f, going = 0f)
        assertEquals(0.555f, early.outgoing.size, e)
        assertEquals(0.333f, early.incoming.size, e)
        val late = MixLook.covers(heard = 0.75f, gains = gains, coming = 1f, going = 0f)
        assertEquals(0.333f, late.outgoing.size, e)
        assertEquals(0.577f, late.incoming.size, e)
    }

    @Test
    fun `coming apart, the incoming cover grows in from further right and up, wholly seen by 60 percent of the way`() {
        val start = MixLook.covers(heard = 0f, gains = null, coming = 0f, going = 0f)
        assertSpot("incoming at the start", 0.666f + 0.044f, 0.310f - 0.044f, 0f, start.incoming)
        assertEquals(1f, start.incoming.cookie, 0f)
        // The way is eased, quadratic in and out: half the time is half the way.
        assertEquals(0.5f, MixLook.eased(0.5f), e)
        assertEquals(0.125f, MixLook.eased(0.25f), e)
        assertEquals(0.875f, MixLook.eased(0.75f), e)
        val half = MixLook.covers(heard = 0f, gains = null, coming = 0.5f, going = 0f)
        assertSpot("incoming half way", 0.666f + 0.022f, 0.310f - 0.022f, 0.333f * 0.5f, half.incoming)
        assertEquals(0.5f / 0.6f, half.incoming.alpha, e)
        assertSpot("outgoing half way", (0.5f + 0.328f) / 2, (0.5f + 0.693f) / 2, (1f + 0.555f) / 2, half.outgoing)
        assertEquals("half turned into the cookie", 0.5f, half.outgoing.cookie, e)
        // 60 % of the way: seen whole from there on.
        var t = 0f
        while (MixLook.eased(t) < 0.6f) t += 0.001f
        assertEquals(1f, MixLook.covers(0f, null, t, 0f).incoming.alpha, e)
        assertTrue(MixLook.covers(0f, null, t - 0.01f, 0f).incoming.alpha < 1f)
    }

    @Test
    fun `coming together at the end, the outgoing cover shrinks away where it is and the incoming one becomes today's cover`() {
        val half = MixLook.covers(heard = 1f, gains = null, coming = 1f, going = 0.5f)
        assertSpot("outgoing half gone", 0.328f, 0.693f, 0.333f * 0.5f, half.outgoing)
        assertEquals(0.5f, half.outgoing.alpha, e)
        assertSpot("incoming half way to the middle", (0.666f + 0.5f) / 2, (0.310f + 0.5f) / 2, (0.577f + 1f) / 2, half.incoming)
        assertEquals("half turned back into the rounded square", 0.5f, half.incoming.cookie, e)
        val end = MixLook.covers(heard = 1f, gains = null, coming = 1f, going = 1f)
        assertEquals(0f, end.outgoing.size, 0f)
        assertEquals(0f, end.outgoing.alpha, 0f)
        assertEquals(MixLook.WHOLE, end.incoming)
    }

    @Test
    fun `the covers take 1_1 s of the music each way, and a change is shown only when it is longer than both`() {
        assertEquals(1_100, MixLook.MORPH_MS)
        assertEquals(2L * MixLook.MORPH_MS, MixOnScreen.SHORTEST_MS)
        assertEquals(0f, MixLook.coming(0), 0f)
        assertEquals(0.5f, MixLook.coming(550), e)
        assertEquals(1f, MixLook.coming(1_100), 0f)
        assertEquals("a player opened late finds them apart", 1f, MixLook.coming(20_000), 0f)
        assertEquals(0f, MixLook.going(-500), 0f)
        assertEquals(1f, MixLook.going(5_000), 0f)
    }

    @Test
    fun `where the incoming song is says where the covers are, from the change's start to after its end`() {
        val change = mockup
        // As the incoming song comes in: today's cover.
        assertEquals(MixLook.WHOLE, MixLook.covers(change, atMs = 0).outgoing)
        // Half way through the covers coming apart.
        assertEquals(MixLook.covers(change.heard(550), null, 0.5f, 0f), MixLook.covers(change, atMs = 550))
        // Apart, sized by the music, half way: the incoming song taking over.
        val half = MixLook.covers(change, atMs = 21_000)
        assertSpot("outgoing half way", 0.328f, 0.693f, 0.333f + 0.222f * cos(PI / 4).toFloat(), half.outgoing)
        // The outgoing song gone: the covers come together over 1.1 s, then the screen is today's.
        assertTrue(MixLook.shows(change, 42_000))
        assertEquals(0.5f, MixLook.covers(change, atMs = 42_550).outgoing.alpha, e)
        assertTrue(MixLook.shows(change, 43_099))
        assertFalse(MixLook.shows(change, 43_100))
        assertEquals(MixLook.WHOLE, MixLook.covers(change, atMs = 43_100).incoming)
        // A jump back inside the change finds them where the music is.
        assertEquals(half, MixLook.covers(change, atMs = 21_000))
    }

    @Test
    fun `a wide picture fills the cookie as a square crop, and is drawn whole in today's square`() {
        val wide = 16f / 9f
        // Today's rounded square: the picture whole, as today, so nothing jumps as the covers part or come together.
        assertEquals(1f, MixLook.cropScale(wide, cookie = 0f), 0f)
        // The cookie: enlarged until its height fills the square, the sides cut, the scallops all round.
        assertEquals(wide, MixLook.cropScale(wide, cookie = 1f), e)
        assertEquals((1f + wide) / 2, MixLook.cropScale(wide, cookie = 0.5f), e)
        assertEquals("tall the same way", wide, MixLook.cropScale(9f / 16f, cookie = 1f), e)
        assertEquals("a square cover as it is", 1f, MixLook.cropScale(1f, cookie = 1f), 0f)
        assertEquals("not known yet: as it is", 1f, MixLook.cropScale(0f, cookie = 1f), 0f)
    }

    @Test
    fun `the title, the buttons' song and the bar are the outgoing song's until the incoming one takes over`() {
        assertTrue(MixLook.showsOutgoing(0f, 0.5f))
        assertTrue(MixLook.showsOutgoing(0.499f, 0.5f))
        assertFalse(MixLook.showsOutgoing(0.5f, 0.5f))
        assertFalse(MixLook.showsOutgoing(1f, 0.5f))
        // The swap is a 300 ms fade through, the outgoing words gone in its first 35 %.
        assertEquals(300, MixLook.SWAP_MS)
        assertEquals(105, MixLook.SWAP_OUT_MS)
    }

    private fun song(id: String, seconds: Int) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = seconds, genre = null)

    /** The mockup he picked from: a 42 s change, the outgoing song at 3:22 of 4:16, the incoming one in from 0:00 of 3:50. */
    private val mockup = MixOnScreen(
        id = 1, outgoing = song("a", 256), outgoingLengthMs = 256_000, outgoingFromMs = 202_000,
        incoming = song("b", 230), entryMs = 0, lengthMs = 42_000, handoverMs = 21_000, gains = null,
    )

    @Test
    fun `before the handover the bar is the outgoing song's, its place when the change began and the time since`() {
        // 10 s into the change: 3:32 of 4:16, the thumb where that share is in the incoming song's range.
        val bar = MixLook.bar(mockup, outgoing = true, at = 10_000, length = 230_000, dragging = false)
        assertEquals(212_000L, bar.at)
        assertEquals(256_000L, bar.length)
        assertEquals(212_000f / 256_000f * 230_000f, bar.thumb, 1f)
    }

    @Test
    fun `after the handover, without a change, and while a finger is on it, the bar is the song the player has`() {
        val after = MixLook.bar(mockup, outgoing = false, at = 25_000, length = 230_000, dragging = false)
        assertEquals(MixLook.Bar(25_000, 230_000, 25_000f), after)
        assertEquals(MixLook.Bar(25_000, 230_000, 25_000f), MixLook.bar(null, outgoing = true, at = 25_000, length = 230_000, dragging = false))
        // A drag seeks in the song the player has, so it is shown in it.
        assertEquals(MixLook.Bar(5_000, 230_000, 5_000f), MixLook.bar(mockup, outgoing = true, at = 5_000, length = 230_000, dragging = true))
    }

    @Test
    fun `the outgoing song's place never runs past its end, nor before the change`() {
        assertEquals(256_000L, MixLook.bar(mockup, outgoing = true, at = 120_000, length = 230_000, dragging = false).at)
        assertEquals(202_000L, mockup.outgoingAt(-3_000))
    }

    @Test
    fun `the backgrounds go from one cover's to the other's as they are drawn today`() {
        for (artwork in listOf(1f, 0.5f, 0.75f)) {
            for (t in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
                val (a, b) = MixLook.backgroundAlphas(t, artwork)
                // The incoming picture over the outgoing one over what is behind both.
                val ofIncoming = b
                val ofOutgoing = (1 - b) * a
                val ofBehind = (1 - b) * (1 - a)
                assertEquals("incoming at $t of $artwork", artwork * t, ofIncoming, e)
                assertEquals("outgoing at $t of $artwork", artwork * (1 - t), ofOutgoing, e)
                assertEquals("behind at $t of $artwork", 1 - artwork, ofBehind, e)
            }
        }
        assertEquals(1f to 0f, MixLook.backgroundAlphas(0f, 1f))
        assertEquals(0f to 1f, MixLook.backgroundAlphas(1f, 1f))
    }

    @Test
    fun `the backgrounds go from the outgoing cover's to the incoming one's in steps`() {
        assertEquals(0f, MixLook.stepped(0f), 0f)
        assertEquals(1f, MixLook.stepped(1f), 0f)
        assertEquals(16f / 32f, MixLook.stepped(0.505f), 0f)
        assertEquals("never outside the change", 1f, MixLook.stepped(1.4f), 0f)
    }

    // The shapes

    /** Points along [cubics], [each] to a cubic. */
    private fun outline(cubics: List<Cubic>, each: Int = 24): List<Pair<Float, Float>> = cubics.flatMap { c ->
        (0 until each).map { k ->
            val t = k / each.toFloat()
            val u = 1 - t
            val x = u * u * u * c.anchor0X + 3 * u * u * t * c.control0X + 3 * u * t * t * c.control1X + t * t * t * c.anchor1X
            val y = u * u * u * c.anchor0Y + 3 * u * u * t * c.control0Y + 3 * u * t * t * c.control1Y + t * t * t * c.anchor1Y
            x to y
        }
    }

    @Test
    fun `the cookie is Material's nine-sided one, a point at the top, in a box of side 1`() {
        val points = outline(MixShapes.cookie9.cubics, each = 200)
        assertTrue(points.all { (x, y) -> x in -1e-3f..1.001f && y in -1e-3f..1.001f })
        // Laid in its box by its control points, as Material lays it (RoundedPolygon.normalized):
        // the curve itself comes a little short of the box's sides.
        val wide = points.maxOf { it.first } - points.minOf { it.first }
        val tall = points.maxOf { it.second } - points.minOf { it.second }
        assertTrue("as big as the box, nearly: $wide by $tall", maxOf(wide, tall) in 0.95f..1.0005f)
        // Its points: the distance from its centre, round the outline, peaks nine times. With an odd
        // number of points it does not sit in the middle of its box; its centre is the mean of its
        // points, each scallop being drawn the same.
        val cx = points.map { it.first }.average().toFloat()
        val cy = points.map { it.second }.average().toFloat()
        assertEquals("centred across", 0.5f, cx, 1e-3f)
        val byAngle = points.map { (x, y) -> atan2(y - cy, x - cx) to hypot(x - cx, y - cy) }.sortedBy { it.first }
        val bins = 360
        val far = FloatArray(bins)
        for ((angle, r) in byAngle) {
            val bin = (((angle + PI) / (2 * PI)) * bins).toInt().coerceIn(0, bins - 1)
            far[bin] = maxOf(far[bin], r)
        }
        val peaks = (0 until bins).count { i ->
            val here = far[i]
            here > 0f && here > far[(i + bins - 1) % bins] && here >= far[(i + 1) % bins] &&
                (1..8).all { d -> here >= far[(i + d) % bins] && here >= far[(i + bins - d) % bins] }
        }
        assertEquals("nine scallops", 9, peaks)
        // One of them straight up: the highest point of the outline is in the middle across.
        val top = points.minBy { it.second }
        assertEquals(0.5f, top.first, 0.02f)
        // Scalloped, not starred: the inner points stand well out, the rounding taking the 0.8 nearer still.
        val depth = far.filter { it > 0f }.let { it.min() / it.max() }
        assertTrue("depth $depth", depth in 0.75f..0.97f)
    }

    @Test
    fun `the morph starts as today's rounded square and ends as the cookie`() {
        val corner = 12f / 360f
        val morph = MixShapes.morph(corner)
        val square = outline(MixShapes.cubics(morph, 0f))
        assertEquals(0f, square.minOf { it.first }, 1e-3f)
        assertEquals(1f, square.maxOf { it.first }, 1e-3f)
        assertEquals(0f, square.minOf { it.second }, 1e-3f)
        assertEquals(1f, square.maxOf { it.second }, 1e-3f)
        // Its corners rounded by the cover's own radius: nothing of it nearer the box's corner than the arc.
        val nearest = square.minOf { (x, y) -> hypot(x, y) }
        assertEquals("the corner's arc", corner * (Math.sqrt(2.0) - 1).toFloat(), nearest, 2e-3f)
        val cookie = outline(MixShapes.cubics(morph, 1f))
        val alone = outline(MixShapes.cookie9.cubics, each = 100)
        // The same outline, cut into other pieces: every point of one lies on the other.
        for ((x, y) in cookie) assertTrue("($x, $y) is off the cookie", alone.minOf { (u, v) -> hypot(u - x, v - y) } < 0.01f)
        // And the outline goes round once either way, never twisted.
        val angles = cookie.map { (x, y) -> atan2(y - 0.5f, x - 0.5f) }
        assertTrue(cookie.isNotEmpty())
        var turned = 0.0
        for (i in angles.indices) {
            var d = (angles[(i + 1) % angles.size] - angles[i]).toDouble()
            if (d > PI) d -= 2 * PI
            if (d < -PI) d += 2 * PI
            turned += d
        }
        assertEquals(2 * PI, kotlin.math.abs(turned), 0.01)
    }

    @Test
    fun `the turn of the cookie is the one Compose's matrix makes, a quarter back`() {
        // Material turns the star a quarter back with Matrix.rotateZ(-90): its first point, on the
        // right, goes to the top. sin and cos as that matrix takes them.
        val r = -90f * (PI / 180.0)
        val s = sin(r).toFloat()
        val c = cos(r).toFloat()
        assertEquals(0f, c * 1f - s * 0f, 1e-6f)
        assertEquals(-1f, s * 1f + c * 0f, 1e-6f)
    }

    // ---- smooth covers (9 Nov 2026: the covers came in slow and choppy on a 120 Hz phone)

    @Test
    fun `the covers' clock moves on with each frame and keeps to the player`() {
        val frame = 8_333_333L                     // 120 Hz
        // Steady: the player says what the frame says.
        assertEquals(1008L, MixLook.paced(1000L, 1008L, frame, playing = true))
        // The player's position in steps: the clock moves on regardless, and closes the gap little by little.
        val stuck = MixLook.paced(1000L, 1000L, frame, playing = true)
        assertTrue("moves on while the player's position stands still: $stuck", stuck in 1001L..1008L)
        // A jump or a pause is taken at once.
        assertEquals(30_000L, MixLook.paced(1000L, 30_000L, frame, playing = true))
        assertEquals(1500L, MixLook.paced(1000L, 1500L, frame, playing = false))
        // No frame time (the screen not drawn): the position as it is.
        assertEquals(1234L, MixLook.paced(1000L, 1234L, 0L, playing = true))
        // Over a second of frames with the position told only four times a second, the clock never goes back
        // and never runs far from it.
        var at = 0L
        var told = 0L
        var t = 0L
        repeat(120) {
            t += frame
            if (t / 250_000_000L != (t - frame) / 250_000_000L) told = t / 1_000_000L
            val next = MixLook.paced(at, told, frame, playing = true)
            assertTrue("back from $at to $next", next >= at || told < at - MixLook.PACE_SNAP_MS)
            at = next
        }
        assertTrue("$at against ${t / 1_000_000L}", kotlin.math.abs(at - t / 1_000_000L) < MixLook.PACE_SNAP_MS)
    }

    @Test
    fun `a cover's turn is made of kept shapes, so a frame of the same step builds nothing`() {
        val morphs = CoverMorphs(androidx.compose.ui.unit.Dp(24f))
        val a = morphs.shape(0.5f)
        assertTrue("the same step, the very same shape", a === morphs.shape(0.5f + 0.1f / CoverMorphs.TURNS))
        assertFalse("the next step, another", a === morphs.shape(0.5f + 1.5f / CoverMorphs.TURNS))
        assertTrue("the rounded square at 0", morphs.shape(0f) === morphs.shape(0.2f / CoverMorphs.TURNS))
        val steps = (0..1000).map { morphs.shape(it / 1000f) }.toSet()
        assertTrue("${steps.size} shapes for a whole turn", steps.size <= CoverMorphs.TURNS + 1)
    }
}
