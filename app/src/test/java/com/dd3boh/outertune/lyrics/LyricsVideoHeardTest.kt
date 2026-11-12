/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.dd3boh.outertune.playback.MusicLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What a lyrics video's Living blur moves with. Every video made until 2 Nov 2026 moved with
 * nothing: the sound's levels were measured, then thrown away by the flush Transformer gives its
 * processors after the sound has ended (seen in the log: 1313 sets measured, 0 heard), and the
 * picture drifted on its own while the player's beat with every kick.
 */
class LyricsVideoHeardTest {

    private val rate = 48_000

    /** [seconds] of a 60 Hz tone that comes and goes twice a second, like a kick, as 16 bit mono. */
    private fun kicks(seconds: Double): ByteBuffer {
        val n = (seconds * rate).roundToInt()
        val buffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        repeat(n) { i ->
            val t = i.toDouble() / rate
            val on = (t * 2) % 1.0 < 0.25
            buffer.putShort(((if (on) 0.6 else 0.02) * sin(2 * PI * 60 * t) * 32767).roundToInt().toShort())
        }
        buffer.flip()
        return buffer
    }

    /** The calls Transformer makes on the way: configure, a flush or two before the sound, the sound, its end, and a flush on the way out. */
    private fun cutAsTransformerDoes(cut: LyricsVideo.SoundCut, seconds: Double) {
        cut.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        cut.flush()
        cut.flush()
        val sound = kicks(seconds)
        while (sound.hasRemaining()) {
            val piece = sound.slice().order(ByteOrder.nativeOrder())
            piece.limit(minOf(piece.remaining(), 4096 * 2))
            cut.queueInput(piece)
            sound.position(sound.position() + piece.position())
            cut.getOutput()
        }
        cut.queueEndOfStream()
        cut.getOutput()
        cut.flush()
        cut.reset()
    }

    @Test
    fun `the levels survive the flush after the sound has ended`() {
        val cut = LyricsVideo.SoundCut(skipMs = 1_000, durationMs = 2_000)
        cutAsTransformerDoes(cut, 3.0)
        val sets = cut.heard()
        // one set every 20 ms, of the whole sound: the second before the stretch too
        assertEquals(150.0, sets.size.toDouble(), 2.0)
        assertTrue("the kicks are heard", sets.maxOf { it[MusicLevels.BASS] } > 0.5f)
        assertTrue("and the gaps between them", sets.minOf { it[MusicLevels.BASS] } < 0.3f)
    }

    @Test
    fun `the flushes before the sound start nothing`() {
        val cut = LyricsVideo.SoundCut(skipMs = 0, durationMs = 1_000)
        cut.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        cut.flush()
        cut.flush()
        assertTrue(cut.heard().isEmpty())
    }

    @Test
    fun `the levels are read before the video's start as far back as they were measured`() {
        val sets = List(150) { floatArrayOf(it.toFloat()) }
        val heard = LyricsVideo.Heard(sets, beforeMs = 1_000)
        assertSame("the first set measured", sets[0], heard.at(-1_000))
        assertNull("nothing before it", heard.at(-1_001))
        assertSame("the video's start", sets[50], heard.at(0))
        assertSame(sets[50], heard.at(19))
        assertSame(sets[51], heard.at(20))
        assertSame("the last after the end", sets[149], heard.at(60_000))
        assertNull("and nothing at all where nothing was heard", LyricsVideo.Heard(emptyList(), beforeMs = 1_000).at(0))
    }
}
