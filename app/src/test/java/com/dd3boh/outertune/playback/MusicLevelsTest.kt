/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The levels the player's living background moves to. The picture should pulse with the kick drum
 * and not with the singer, move as much for a quiet recording as for a loud one, and stay still in
 * silence.
 */
class MusicLevelsTest {

    private val rate = 48_000

    /** Every frame the analyser gave for [signal]. */
    private fun frames(signal: FloatArray, sampleRate: Int = rate): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val analyser = LevelAnalyser(sampleRate) { _, levels -> out += levels.copyOf() }
        signal.forEach(analyser::sample)
        return out
    }

    private fun tone(hz: Double, seconds: Double, loud: Float = 0.5f, sampleRate: Int = rate) =
        FloatArray((seconds * sampleRate).toInt()) { loud * sin(2 * PI * hz * it / sampleRate).toFloat() }

    /** A kick drum of sorts: [hz] for 80 ms at every half second. */
    private fun kicks(seconds: Double, hz: Double = 55.0, loud: Float = 0.8f) =
        FloatArray((seconds * rate).toInt()) {
            val inBeat = it % (rate / 2)
            if (inBeat < rate * 0.08) loud * sin(2 * PI * hz * inBeat / rate).toFloat() else 0f
        }

    private fun List<FloatArray>.settled() = drop(size / 2)
    private fun List<FloatArray>.mean(band: Int) = map { it[band] }.average().toFloat()

    /** How loud each range is against full scale, before anything is set against the song. */
    private fun raw(signal: FloatArray): FloatArray {
        val out = mutableListOf<FloatArray>()
        lateinit var analyser: LevelAnalyser
        analyser = LevelAnalyser(rate) { _, _ -> out += analyser.raw.copyOf() }
        signal.forEach(analyser::sample)
        val settled = out.settled()
        return FloatArray(MusicLevels.BANDS) { settled.mean(it) }
    }

    // The ranges are measured against themselves, so what leaks from one into an emptier one is
    // stretched up with it. These hold the leaks down.

    @Test
    fun `a bass note is bass`() {
        val (bass, mid, high) = raw(tone(60.0, 2.0)).toList()
        assertTrue("bass $bass of 0.35", bass > 0.25f)
        assertTrue("mid $mid against bass $bass", mid < bass * 0.25f)
        assertTrue("high $high against bass $bass", high < bass * 0.02f)
    }

    @Test
    fun `a note in the middle is the middle`() {
        val (bass, mid, high) = raw(tone(800.0, 2.0)).toList()
        assertTrue("mid $mid of 0.35", mid > 0.28f)
        assertTrue("bass $bass against mid $mid", bass < mid * 0.06f)
        assertTrue("high $high against mid $mid", high < mid * 0.2f)
    }

    @Test
    fun `a cymbal is the top`() {
        val (bass, mid, high) = raw(tone(9000.0, 2.0)).toList()
        assertTrue("high $high of 0.35", high > 0.2f)
        assertTrue("mid $mid against high $high", mid < high * 0.15f)
        assertTrue("bass $bass against high $high", bass < high * 0.01f)
    }

    @Test
    fun `a voice is not bass`() {
        // a sung note around 300 Hz
        val (bass, mid, _) = raw(tone(300.0, 2.0)).toList()
        assertTrue("bass $bass against mid $mid", bass < mid * 0.3f)
    }

    @Test
    fun `a range with next to nothing in it is not stretched to full`() {
        // kicks, and a hiss at the top a hundred times quieter, for long enough to forget an ordinary song
        val hiss = tone(9000.0, 30.0, loud = 0.008f)
        val mixed = kicks(30.0).mapIndexed { i, x -> x + hiss[i] }.toFloatArray()
        val high = frames(mixed).takeLast(200).map { it[MusicLevels.HIGH] }
        assertTrue("the top reads ${high.max()}", high.max() < 0.2f)
    }

    @Test
    fun `a kick drum stands out from the gap after it`() {
        val bass = frames(kicks(6.0)).settled().map { it[MusicLevels.BASS] }
        assertTrue("loudest ${bass.max()}", bass.max() > 0.85f)
        assertTrue("quietest ${bass.min()}", bass.min() < 0.05f)
        // most of the half second between two kicks is quiet
        assertTrue("share of quiet frames ${bass.count { it < 0.2f } / bass.size.toFloat()}", bass.count { it < 0.2f } > bass.size * 0.6)
    }

    @Test
    fun `a kick drum under a voice still stands out`() {
        val voice = tone(300.0, 6.0, loud = 0.4f)
        val mixed = kicks(6.0).mapIndexed { i, x -> (x + voice[i]) * 0.7f }.toFloatArray()
        val bass = frames(mixed).settled().map { it[MusicLevels.BASS] }.sorted()
        val quietHalf = bass.take(bass.size / 2).average()
        assertTrue("loudest ${bass.last()}", bass.last() > 0.85f)
        assertTrue("the gaps $quietHalf", quietHalf < 0.3)
    }

    @Test
    fun `silence is still`() {
        frames(FloatArray(rate * 2)).forEach { assertArrayEquals(floatArrayOf(0f, 0f, 0f), it, 0f) }
        // and so is the hiss before a song: about -60 dB
        val hiss = FloatArray(rate * 2) { if (it % 2 == 0) 0.001f else -0.001f }
        frames(hiss).forEach { assertArrayEquals(floatArrayOf(0f, 0f, 0f), it, 0f) }
    }

    @Test
    fun `a quiet recording moves as much as a loud one, after a while`() {
        val quiet = frames(kicks(40.0, loud = 0.03f)).map { it[MusicLevels.BASS] }
        val early = quiet.take(quiet.size / 8).max()
        val late = quiet.takeLast(quiet.size / 4).max()
        assertTrue("at first it is small against an ordinary song: $early", early < 0.4f)
        assertTrue("then it is measured against itself: $late", late > 0.85f)
    }

    @Test
    fun `the loudest moment stays the yardstick for a while`() {
        val loudThenSoft = tone(60.0, 1.0, loud = 0.9f) + tone(60.0, 1.0, loud = 0.3f)
        val bass = frames(loudThenSoft).map { it[MusicLevels.BASS] }
        val soft = bass.takeLast(bass.size / 4).average()
        assertTrue("a third as loud reads as about a third: $soft", soft > 0.25 && soft < 0.5)
    }

    @Test
    fun `a frame is 20 ms whatever the sample rate`() {
        for (sampleRate in listOf(44_100, 48_000, 96_000)) {
            val ends = mutableListOf<Long>()
            val analyser = LevelAnalyser(sampleRate) { end, _ -> ends += end }
            repeat(sampleRate) { analyser.sample(0f) }
            assertEquals("$sampleRate", 50, ends.size)
            assertEquals("$sampleRate", sampleRate / 50L, ends.first())
            assertEquals("$sampleRate", sampleRate.toLong(), ends.last())
        }
    }

    @Test
    fun `16 bit and float samples read the same, and both channels count`() {
        val mono = kicks(3.0)
        val want = frames(mono)

        val as16 = ByteBuffer.allocate(mono.size * 4).order(ByteOrder.nativeOrder())
        mono.forEach { x -> repeat(2) { as16.putShort((x * 32767).toInt().toShort()) } }
        as16.flip()
        val got16 = mutableListOf<FloatArray>()
        LevelAnalyser(rate) { _, levels -> got16 += levels.copyOf() }.pcm16(as16, channels = 2)

        val asFloat = ByteBuffer.allocate(mono.size * 8).order(ByteOrder.nativeOrder())
        mono.forEach { x -> repeat(2) { asFloat.putFloat(x) } }
        asFloat.flip()
        val gotFloat = mutableListOf<FloatArray>()
        LevelAnalyser(rate) { _, levels -> gotFloat += levels.copyOf() }.pcmFloat(asFloat, channels = 2)

        assertEquals(want.size, got16.size)
        assertEquals(want.size, gotFloat.size)
        want.indices.forEach {
            assertArrayEquals("frame $it, 16 bit", want[it], got16[it], 0.01f)
            assertArrayEquals("frame $it, float", want[it], gotFloat[it], 0.0001f)
        }
        assertEquals("the buffer is the player's, it must be left alone", 0, as16.position())
        assertEquals(mono.size * 4, as16.limit())
        assertEquals(0, asFloat.position())
    }

    @Test
    fun `only what lies between position and limit is read`() {
        val buffer = ByteBuffer.allocate(4000).order(ByteOrder.nativeOrder())
        repeat(2000) { buffer.putShort(20000) }
        buffer.position(1000).limit(1000 + 960 * 2)        // exactly one frame of one channel
        var count = 0
        LevelAnalyser(rate) { _, _ -> count++ }.pcm16(buffer, channels = 1)
        assertEquals(1, count)
    }

    @Test
    fun `a sample that is not a number spoils its own frame and nothing after`() {
        val signal = kicks(4.0)
        signal[rate + 123] = Float.NaN
        signal[rate * 2 + 7] = Float.POSITIVE_INFINITY
        val heard = frames(signal)
        assertTrue("every level is a number between 0 and 1", heard.all { f -> f.all { it in 0f..1f } })
        assertTrue("and the kicks after it still show: ${heard.takeLast(60).maxOf { it[MusicLevels.BASS] }}", heard.takeLast(60).maxOf { it[MusicLevels.BASS] } > 0.85f)
    }

    @Test
    fun `a jump drops the half measured frame`() {
        val ends = mutableListOf<Long>()
        val analyser = LevelAnalyser(rate) { end, _ -> ends += end }
        repeat(500) { analyser.sample(0.5f) }
        analyser.jump()
        repeat(960) { analyser.sample(0.5f) }
        assertEquals("one whole frame after the jump, not one ending 460 samples in", listOf(1460L), ends)
    }

    // The timeline

    private fun levels(x: Float) = floatArrayOf(x, x / 2, x / 4)

    @Test
    fun `a level is read by the time it is heard`() {
        val timeline = LevelTimeline()
        for (i in 0 until 100) timeline.add(1_000_000L + i * 20_000L, levels(i / 100f))
        val got = FloatArray(3)
        assertTrue(timeline.read(1_000_000L + 40 * 20_000L, got))
        assertArrayEquals(levels(0.40f), got, 0f)
        // between two frames it is the one already heard, not the one to come
        assertTrue(timeline.read(1_000_000L + 40 * 20_000L + 19_999L, got))
        assertArrayEquals(levels(0.40f), got, 0f)
    }

    @Test
    fun `nothing is known before the first level or long after the last`() {
        val timeline = LevelTimeline()
        val got = FloatArray(3)
        assertFalse("empty", timeline.read(0, got))
        for (i in 0 until 10) timeline.add(1_000_000L + i * 20_000L, levels(0.5f))
        assertFalse("before the first", timeline.read(999_999L, got))
        assertTrue("just after the last", timeline.read(1_180_000L + 100_000L, got))
        assertFalse("the player ran dry: the last level is not held for ever", timeline.read(1_180_000L + 300_000L, got))
    }

    @Test
    fun `only the last few seconds are kept`() {
        val timeline = LevelTimeline(capacity = 50)
        for (i in 0 until 500) timeline.add(i * 20_000L, levels(i / 500f))
        val got = FloatArray(3)
        assertTrue(timeline.read(499 * 20_000L, got))
        assertArrayEquals(levels(499 / 500f), got, 0f)
        assertTrue(timeline.read(450 * 20_000L, got))
        assertArrayEquals(levels(450 / 500f), got, 0f)
        assertFalse("long gone", timeline.read(449 * 20_000L, got))
    }

    @Test
    fun `after a seek the old levels are gone`() {
        val timeline = LevelTimeline()
        for (i in 0 until 10) timeline.add(60_000_000L + i * 20_000L, levels(0.9f))
        timeline.clear()
        val got = FloatArray(3)
        assertFalse(timeline.read(60_100_000L, got))
        timeline.add(5_000_000L, levels(0.1f))
        assertTrue(timeline.read(5_010_000L, got))
        assertArrayEquals(levels(0.1f), got, 0f)
    }
}
