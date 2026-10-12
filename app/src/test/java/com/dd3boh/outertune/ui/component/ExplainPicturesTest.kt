/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.playback.BinauralAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The picture in the explanation of Fade between tracks shows what the setting does, and not a
 * crossfade, and it moves the way sound does: nothing jumps, nothing moves as one block, and the
 * loop has no seam.
 */
class ExplainPicturesTest {

    private val bars = 0 until FADE_BARS

    /** Every 240th of a second through one loop, and a little past its end. */
    private val moments = generateSequence(0f) { it + 1f / 240f }.takeWhile { it < FADE_LOOP + 0.5f }.toList()

    /** One frame of a screen that draws 60 a second, the slowest there is. */
    private val frame = 1f / 60f

    private fun loudest(song: Int, at: Float) = bars.maxOf { fadeLevel(song, it, at) }

    @Test
    fun `it opens on the left song sounding and the right one silent`() {
        for (bar in bars) {
            assertEquals(1f, fadeLevel(0, bar, 0f), 0f)
            assertEquals(0f, fadeLevel(1, bar, 0f), 0f)
        }
    }

    @Test
    fun `half way round it is the right song that sounds`() {
        for (bar in bars) {
            assertEquals(0f, fadeLevel(0, bar, FADE_PASS), 0f)
            assertEquals(1f, fadeLevel(1, bar, FADE_PASS), 0f)
        }
    }

    @Test
    fun `the two songs never sound at once`() {
        for (at in moments) {
            assertTrue("both sound at $at", minOf(loudest(0, at), loudest(1, at)) == 0f)
        }
    }

    @Test
    fun `there is a moment of nothing each time one hands over to the other`() {
        for (pass in 0..1) {
            val quiet = moments.count {
                it >= pass * FADE_PASS && it < (pass + 1) * FADE_PASS && loudest(0, it) == 0f && loudest(1, it) == 0f
            }
            assertTrue("only ${quiet / 240f} s of nothing in half $pass", quiet / 240f >= FADE_GAP - 0.02f)
        }
    }

    @Test
    fun `every bar stays between nothing and full`() {
        for (song in 0..1) for (bar in bars) for (at in moments) {
            assertTrue(fadeLevel(song, bar, at) in 0f..1f)
        }
    }

    @Test
    fun `no bar ever jumps, at the loop's seam least of all`() {
        for (song in 0..1) for (bar in bars) for (at in moments) {
            val step = abs(fadeLevel(song, bar, at + frame) - fadeLevel(song, bar, at))
            assertTrue("bar $bar of song $song jumps by $step at $at", step <= 0.04f)
        }
    }

    @Test
    fun `the loop comes back to where it began`() {
        for (song in 0..1) for (bar in bars) for (at in listOf(0f, 0.7f, 2.1f, 3.3f, 5.9f, 7.7f)) {
            assertEquals(fadeLevel(song, bar, at), fadeLevel(song, bar, at + FADE_LOOP), 0.001f)
        }
    }

    @Test
    fun `a song does not sink or rise as one block`() {
        // Part of the way down, its bars are at different heights.
        val falling = FADE_HOLD + (FADE_FALL + FADE_SWEEP) / 2f
        val levels = bars.map { fadeLevel(0, it, falling) }
        assertTrue(levels.max() - levels.min() > 0.15f)
        val rising = FADE_PASS - (FADE_FALL + FADE_SWEEP) / 2f
        val up = bars.map { fadeLevel(1, it, rising) }
        assertTrue(up.max() - up.min() > 0.15f)
    }

    @Test
    fun `the change travels from the song that is ending to the one that is starting`() {
        // Left to right while the left song hands over: its left bars are down first, and the
        // right song's left bars are up first. The other way round on the way back.
        for (at in moments.filter { it < FADE_PASS }) for (bar in 1 until FADE_BARS) {
            assertTrue(fadeLevel(0, bar - 1, at) <= fadeLevel(0, bar, at))
            assertTrue(fadeLevel(1, bar - 1, at) >= fadeLevel(1, bar, at))
        }
        for (at in moments.filter { it >= FADE_PASS && it < FADE_LOOP }) for (bar in 1 until FADE_BARS) {
            assertTrue(fadeLevel(1, bar - 1, at) >= fadeLevel(1, bar, at))
            assertTrue(fadeLevel(0, bar - 1, at) <= fadeLevel(0, bar, at))
        }
    }

    @Test
    fun `a bar that sounds is never still, never taller than its place, and never jerks`() {
        for (song in 0..1) for (bar in bars) {
            val shares = moments.map { fadeSway(song, bar, it) }
            assertTrue(shares.all { it > 0.4f && it <= 1f })
            assertTrue("bar $bar of song $song hardly moves", shares.max() - shares.min() > 0.2f)
            for (at in moments) {
                assertTrue(abs(fadeSway(song, bar, at + frame) - fadeSway(song, bar, at)) <= 0.05f)
            }
        }
    }

    @Test
    fun `neighbours sway together and the two songs do not sway alike`() {
        for (at in moments) for (bar in 1 until FADE_BARS) {
            assertTrue(abs(fadeSway(0, bar, at) - fadeSway(0, bar - 1, at)) < 0.35f)
        }
        assertTrue(moments.any { at -> bars.any { abs(fadeSway(0, it, at) - fadeSway(1, it, at)) > 0.2f } })
    }

    @Test
    fun `held still, it shows the fall, the silence and the rise from left to right`() {
        val left = bars.map { fadeStill(0, it) }
        val right = bars.map { fadeStill(1, it) }
        assertEquals(1f, left.first(), 0f)
        assertEquals(0f, left.last(), 0f)
        assertEquals(0f, right.first(), 0f)
        assertEquals(1f, right.last(), 0f)
        assertTrue(left.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(right.zipWithNext().all { (a, b) -> a <= b })
        for (song in listOf(left, right)) {
            assertTrue(song.count { it == 1f } >= 3)
            assertTrue(song.count { it > 0f && it < 1f } >= 3)
            assertTrue(song.count { it == 0f } >= 2)
        }
    }

    /** Every 240th of a second through two turns of the head. */
    private val turning = generateSequence(0f) { it + 1f / 240f }.takeWhile { it < 2 * TURN_LOOP }.toList()

    @Test
    fun `the head opens facing the music, looks one way, then the other, and comes back`() {
        assertEquals(0f, headTurn(0f), 0.001f)
        val turns = turning.map { headTurn(it) }
        assertTrue(turns.all { abs(it) <= TURN_MOST + 0.001f })
        assertTrue(turns.max() > TURN_MOST * 0.98f)
        assertTrue(turns.min() < -TURN_MOST * 0.98f)
        // To the right first, and the second half of a loop is the first one mirrored.
        assertTrue(headTurn(TURN_LOOP / 4f) > 0f)
        for (at in listOf(0.3f, 1.1f, 2.6f, 4.9f)) {
            assertEquals(headTurn(at), headTurn(at + TURN_LOOP), 0.02f)
            assertEquals(headTurn(at), -headTurn(at + TURN_LOOP / 2f), 0.02f)
        }
    }

    @Test
    fun `the head never jerks, and holds its look before it turns back`() {
        for (at in turning) {
            val step = abs(headTurn(at + frame) - headTurn(at))
            assertTrue("the head jumps $step degrees at $at", step <= 2f)
        }
        // A plain to and fro spends two fifths of its time past four fifths of the way.
        val lingering = turning.count { abs(headTurn(it)) > TURN_MOST * 0.8f } / turning.size.toFloat()
        assertTrue("the head only lingers $lingering of the time", lingering > 0.5f)
    }

    @Test
    fun `the head turns past the speakers on both sides, and is held still turned`() {
        // The speakers of that picture stand where the listener has set them, and unset that is
        // where stereo is mixed for. The head turns further than that either way.
        assertTrue(TURN_MOST > BinauralAudioProcessor.DEFAULT_STAGE_WIDTH)
        assertTrue(abs(STILL_TURN) in 10f..TURN_MOST)
    }

    @Test
    fun `wherever the slider puts the speakers, they are in the picture`() {
        // A picture at least twice as wide as it is tall, which every place it is shown gives it.
        val widths = generateSequence(BinauralAudioProcessor.MIN_STAGE_WIDTH) { it + 1f }
            .takeWhile { it <= BinauralAudioProcessor.MAX_STAGE_WIDTH }.toList()
        for (width in widths) {
            val (right, down) = stageSpot(width)
            assertTrue("$width degrees is off the side", right + STAGE_SPEAKER <= 1f)
            assertTrue("$width degrees is off the top", down - STAGE_SPEAKER >= 0f)
            assertTrue("$width degrees is off the foot", down + STAGE_SPEAKER <= 1f)
        }
        // Wider is further out and further round towards the ears, and never a jump for one degree.
        for ((narrower, wider) in widths.zipWithNext()) {
            assertTrue(stageSpot(wider).first > stageSpot(narrower).first)
            assertTrue(stageSpot(wider).second > stageSpot(narrower).second)
            assertTrue(stageSpot(wider).first - stageSpot(narrower).first < 0.012f)
        }
        // At a quarter turn they are level with the ears, and straight ahead is straight up.
        assertEquals(STAGE_HEAD_AT, stageSpot(90f).second, 0.001f)
        assertEquals(0f, stageSpot(0f).first, 0.001f)
        // The listener's own head is in the picture too.
        assertTrue(STAGE_HEAD_AT + STAGE_HEAD <= 1f)
    }

    @Test
    fun `with none of the effect the sound is in the headphones, and it comes out as the effect grows`() {
        val widths = generateSequence(BinauralAudioProcessor.MIN_STAGE_WIDTH) { it + 5f }
            .takeWhile { it <= BinauralAudioProcessor.MAX_STAGE_WIDTH }.toList()
        for (width in widths) {
            // At nothing it is in the cup over the ear, wherever the speaker stands.
            assertEquals(STAGE_EAR, stageSpot(width, 0f).first, 0.0001f)
            assertEquals(STAGE_HEAD_AT, stageSpot(width, 0f).second, 0.0001f)
            // At all of it, it is at its speaker.
            assertEquals(stageSpot(width), stageSpot(width, 1f))
            // In between it is further from the head with every step, and never jumps.
            fun fromHead(out: Float) = stageSpot(width, out).let { (right, down) -> kotlin.math.hypot(right, STAGE_HEAD_AT - down) }
            for (step in 1..100) {
                val before = fromHead((step - 1) / 100f)
                val now = fromHead(step / 100f)
                assertTrue("at $width degrees, ${step}% is not further out than the step before", now > before)
                assertTrue(now - before < 0.01f)
            }
        }
        // More than all and less than none are all and none.
        assertEquals(stageSpot(30f, 1f), stageSpot(30f, 1.4f))
        assertEquals(stageSpot(30f, 0f), stageSpot(30f, -0.2f))
    }

    @Test
    fun `a ripple is born and dies unseen, and they follow one another evenly`() {
        for (number in 0 until RIPPLES) for (at in turning) {
            val (far, strength) = ripple(number, at)
            assertTrue(far >= 0f && far < 1f)
            assertTrue(strength in 0f..1f)
            // Near either end of its way it is nearly nothing.
            if (far < 0.02f || far > 0.98f) assertTrue(strength < 0.1f)
            assertTrue(abs(ripple(number, at + frame).second - strength) <= 0.05f)
        }
        for (at in listOf(0f, 0.5f, 1.3f)) {
            val ways = (0 until RIPPLES).map { ripple(it, at).first }.sorted()
            for ((a, b) in ways.zipWithNext()) assertEquals(1f / RIPPLES, b - a, 0.001f)
        }
    }

    @Test
    fun `the room holds the listener and the speakers wherever they stand`() {
        val widths = generateSequence(BinauralAudioProcessor.MIN_STAGE_WIDTH) { it + 1f }
            .takeWhile { it <= BinauralAudioProcessor.MAX_STAGE_WIDTH }.toList()
        for (width in widths) {
            val (right, down) = stageSpot(width)
            assertTrue(right + STAGE_SPEAKER < ROOM_HALF - 0.1f)
            assertTrue(down - STAGE_SPEAKER > ROOM_FRONT + 0.05f)
        }
        assertTrue(STAGE_HEAD_AT + STAGE_HEAD < ROOM_BACK)
        // And the room itself is in the picture, which is at least twice as wide as it is tall.
        assertTrue(ROOM_HALF < 1f && ROOM_FRONT > 0f && ROOM_BACK < 1f)
    }

    @Test
    fun `an echo comes from as far behind the wall as the sound is in front of it`() {
        for (width in listOf(15f, 30f, 60f, 90f)) for (out in listOf(0f, 0.5f, 1f)) {
            val sound = stageSpot(width, out)
            val echo = echoSpot(sound)
            assertEquals(ROOM_HALF - sound.first, echo.first - ROOM_HALF, 0.0001f)
            assertEquals(sound.second, echo.second, 0f)
            assertTrue("the echo of $width degrees is inside the room", echo.first > ROOM_HALF)
        }
    }

    @Test
    fun `the room sound leaves its speaker for the listener's side, and goes no further`() {
        for (width in listOf(15f, 30f, 44f, 75f, 90f)) {
            assertEquals(width, ambienceAngle(width, 0f), 0f)
            assertEquals(90f, ambienceAngle(width, 1f), 0.001f)
            var before = ambienceAngle(width, 0f)
            for (step in 1..100) {
                val now = ambienceAngle(width, step / 100f)
                assertTrue(now >= before && now <= 90.001f)
                // A step of the slider is never a jump in the picture.
                assertTrue(now - before <= 1f)
                before = now
            }
            // More than all and less than none are all and none.
            assertEquals(ambienceAngle(width, 1f), ambienceAngle(width, 3f), 0f)
            assertEquals(ambienceAngle(width, 0f), ambienceAngle(width, -1f), 0f)
        }
    }

    @Test
    fun `the low rings round a cup are born and die unseen, one after the other`() {
        for (number in 0 until THUMPS) for (at in turning) {
            val (spread, strength) = thump(number, at)
            assertTrue(spread >= 0f && spread < 1f)
            assertTrue(strength in 0f..1f)
            if (spread < 0.02f || spread > 0.98f) assertTrue(strength < 0.1f)
            assertTrue(abs(thump(number, at + frame).second - strength) <= 0.06f)
        }
        val spreads = (0 until THUMPS).map { thump(it, 0.4f).first }.sorted()
        for ((a, b) in spreads.zipWithNext()) assertEquals(1f / THUMPS, b - a, 0.001f)
        // Slower than the ripples of the music: low notes.
        assertTrue(THUMP_SECONDS > 1f)
    }

    @Test
    fun `a stage that sets nothing but its width is the plain effect`() {
        val plain = SoundStage(width = 30f)
        assertEquals(1f, plain.strength, 0f)
        assertEquals(0f, plain.room, 0f)
        assertEquals(0f, plain.ambience, 0f)
        assertTrue(!plain.bassAtEars)
    }

    /** Every 240th of a second through two walks away and back. */
    private val walking = generateSequence(0f) { it + 1f / 240f }.takeWhile { it < 2 * WALK_LOOP }.toList()

    @Test
    fun `the listener starts beside the phone, walks to the far end and comes back, without a jerk`() {
        assertEquals(0f, walkAway(0f), 0.001f)
        assertEquals(1f, walkAway(WALK_LOOP / 2f), 0.001f)
        for (at in walking) {
            assertTrue(walkAway(at) in 0f..1f)
            assertTrue(abs(walkAway(at + frame) - walkAway(at)) <= 0.02f)
        }
        for (at in listOf(0.5f, 2.2f, 4.1f)) assertEquals(walkAway(at), walkAway(at + WALK_LOOP), 0.001f)
    }

    @Test
    fun `they face the way they walk, and turn round at each end`() {
        // To the right on the way out and to the left on the way back.
        assertTrue(walkFacing(WALK_LOOP * 0.25f) > 80f)
        assertTrue(walkFacing(WALK_LOOP * 0.75f) < -80f)
        for (at in walking) {
            val going = walkAway(at + frame) - walkAway(at)
            if (abs(going) > 0.001f) assertTrue("facing the wrong way at $at", walkFacing(at + frame / 2f) * going > 0f)
            assertTrue(abs(walkFacing(at + frame) - walkFacing(at)) <= 4f)
        }
    }

    @Test
    fun `the music is quieter the further they are, changes only while they walk, and is never gone`() {
        val levels = walking.map { walkLevel(it) }
        assertTrue(levels.all { it >= WALK_QUIETEST - 0.001f && it <= 1f })
        assertEquals(1f, walkLevel(0f), 0.001f)
        assertEquals(WALK_QUIETEST, walkLevel(WALK_LOOP / 2f), 0.001f)
        // Never gone, and far enough from full to be seen at a glance.
        assertTrue(WALK_QUIETEST in 0.2f..0.4f)
        for (at in walking) {
            val walked = walkAway(at + frame) - walkAway(at)
            val changed = walkLevel(at + frame) - walkLevel(at)
            // Step for step with the walking: it changes as much as they move, the other way,
            // and while they stand it holds.
            assertEquals(-(1f - WALK_QUIETEST) * walked, changed, 0.0005f)
            if (abs(walked) < 0.0005f) assertTrue("the music changes while they stand, at $at", abs(changed) < 0.0005f)
        }
        // Where they are says how loud it is, on the way out and on the way back alike.
        assertEquals(walkLevel(WALK_LOOP * 0.2f), walkLevel(WALK_LOOP * 0.8f), 0.001f)
        // Held still, it shows them part of the way out with the music already less.
        assertTrue(walkAway(STILL_WALK) in 0.3f..0.9f)
        assertTrue(walkLevel(STILL_WALK) < 0.9f)
        assertTrue(walkFacing(STILL_WALK) > 45f)
    }
}
