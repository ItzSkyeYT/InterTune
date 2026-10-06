/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The tap is handed samples well before they are heard. What it answers has to be about the
 * moment being heard, or the picture moves ahead of the music.
 */
class LevelTapTest {

    private val rate = 48_000
    private var nanos = 1_000_000_000L
    private val tap = LevelTap { nanos }.apply {
        watch()
        configured(pcm = true, encoding = C.ENCODING_PCM_16BIT, sampleRate = rate, channels = 2)
        playing(true)
    }
    private val got = FloatArray(MusicLevels.BANDS)

    /** Where the first sample of the test's audio sits in the player's time. */
    private val start = 1_000_000_000_000L

    /** A loud bass note from [fromMs] to [toMs], silence around it, as 16 bit stereo. */
    private fun bassBetween(fromMs: Int, toMs: Int, totalMs: Int): ShortArray {
        val out = ShortArray(totalMs * rate / 1000 * 2)
        for (frame in fromMs * rate / 1000 until toMs * rate / 1000) {
            val x = (0.8 * sin(2 * PI * 60.0 * frame / rate) * 32767).toInt().toShort()
            out[frame * 2] = x
            out[frame * 2 + 1] = x
        }
        return out
    }

    /** Hands [samples] to the tap in buffers of [bufferMs], the way the player does. */
    private fun give(samples: ShortArray, firstUs: Long = start, bufferMs: Int = 10, offerTwice: Boolean = false) {
        val perBuffer = bufferMs * rate / 1000 * 2
        var at = 0
        while (at < samples.size) {
            val count = minOf(perBuffer, samples.size - at)
            val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
            buffer.asShortBuffer().put(samples, at, count)
            val timeUs = firstUs + (at / 2) * 1_000_000L / rate
            tap.buffer(buffer, timeUs)
            if (offerTwice) {
                // the output took half of it and is offered the rest: same buffer, same time
                buffer.position(count)
                tap.buffer(buffer, timeUs)
            }
            at += count
        }
    }

    private fun bassAt(ms: Int): Float? {
        tap.position(start + ms * 1000L)
        return if (tap.now(got)) got[MusicLevels.BASS] else null
    }

    @Test
    fun `the answer is about the moment being heard, not the last one handed over`() {
        // everything for the next second is handed over at once, as it is to a real output
        give(bassBetween(400, 700, totalMs = 1000))
        assertTrue("before the note: ${bassAt(300)}", bassAt(300)!! < 0.05f)
        assertTrue("in the note: ${bassAt(550)}", bassAt(550)!! > 0.8f)
        assertTrue("after the note: ${bassAt(900)}", bassAt(900)!! < 0.05f)
    }

    @Test
    fun `between two askings the clock runs on`() {
        give(bassBetween(400, 700, totalMs = 1000))
        tap.position(start + 380_000L)
        assertTrue(tap.now(got))
        assertTrue("20 ms before the note: ${got[MusicLevels.BASS]}", got[MusicLevels.BASS] < 0.05f)
        nanos += 60_000_000L                                   // 60 ms later, the output not asked again yet
        assertTrue(tap.now(got))
        assertTrue("40 ms into the note: ${got[MusicLevels.BASS]}", got[MusicLevels.BASS] > 0.5f)
    }

    @Test
    fun `a buffer the output is offered twice is measured once`() {
        give(bassBetween(400, 700, totalMs = 1000), offerTwice = true)
        // measured twice, the note would sit at twice its time
        assertTrue("in the note: ${bassAt(550)}", bassAt(550)!! > 0.8f)
        assertTrue("after the note: ${bassAt(900)}", bassAt(900)!! < 0.05f)
    }

    @Test
    fun `nothing is measured while nobody is looking`() {
        tap.unwatch()
        give(bassBetween(0, 1000, totalMs = 1000))
        tap.position(start + 500_000L)
        assertFalse(tap.now(got))
    }

    @Test
    fun `looking again picks up from there, without a level left over from before`() {
        give(bassBetween(0, 205, totalMs = 205))               // ends in the middle of a 20 ms measure
        tap.unwatch()
        give(ShortArray(rate / 1000 * 2 * 300), firstUs = start + 205_000L)
        tap.watch()
        give(ShortArray(rate / 1000 * 2 * 300), firstUs = start + 505_000L)
        // the first measure after looking again: 505 to 525 ms, or with 5 ms of the old note in it were that kept
        assertTrue("silence, with nothing of the old note in it: ${bassAt(522)}", bassAt(522)!! == 0f)
    }

    @Test
    fun `it is wanted for as long as anyone is looking`() {
        tap.watch()                                            // a second onlooker
        tap.unwatch()
        assertTrue("one of two stopped looking", tap.wanted)
        tap.unwatch()
        assertFalse(tap.wanted)
        tap.unwatch()                                          // one too many
        tap.watch()
        assertTrue("an unwatch too many does not eat the next watch", tap.wanted)
    }

    @Test
    fun `a seek forgets what was about to be heard`() {
        give(bassBetween(0, 1000, totalMs = 1000))
        assertTrue(bassAt(500)!! > 0.8f)
        tap.flushed()
        assertFalse("nothing yet at the new place", tap.now(got))
        val there = start + 90_000_000L
        give(ShortArray(rate * 2), firstUs = there)
        tap.position(there + 500_000L)
        assertTrue(tap.now(got))
        assertTrue("silence at the new place: ${got[MusicLevels.BASS]}", got[MusicLevels.BASS] == 0f)
    }

    @Test
    fun `nothing playing, no answer`() {
        give(bassBetween(0, 1000, totalMs = 1000))
        tap.position(start + 500_000L)
        tap.playing(false)
        assertFalse("paused", tap.now(got))
        tap.playing(true)
        assertTrue(tap.now(got))
        nanos += 600_000_000L
        assertFalse("the output has not been asked where it is for over half a second", tap.now(got))
        tap.position(AudioSink.CURRENT_POSITION_NOT_SET)
        assertFalse("an output that does not know where it is leaves the clock alone", tap.now(got))
    }

    @Test
    fun `audio that goes out still encoded gives no answer`() {
        tap.configured(pcm = false, encoding = C.ENCODING_OPUS, sampleRate = rate, channels = 2)
        give(bassBetween(0, 1000, totalMs = 1000))
        tap.position(start + 500_000L)
        assertFalse(tap.now(got))
    }

    @Test
    fun `float samples are read too`() {
        tap.configured(pcm = true, encoding = C.ENCODING_PCM_FLOAT, sampleRate = rate, channels = 1)
        val frames = rate
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) buffer.putFloat((0.8 * sin(2 * PI * 60.0 * frame / rate)).toFloat())
        buffer.flip()
        tap.buffer(buffer, start)
        assertTrue("${bassAt(500)}", bassAt(500)!! > 0.8f)
    }

    @Test
    fun `a format that cannot be read is left alone`() {
        tap.configured(pcm = true, encoding = C.ENCODING_PCM_24BIT, sampleRate = rate, channels = 2)
        give(bassBetween(0, 1000, totalMs = 1000))
        tap.position(start + 500_000L)
        assertFalse(tap.now(got))
    }
}
