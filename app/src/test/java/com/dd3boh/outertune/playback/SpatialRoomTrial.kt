/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Not a test: numbers to read. What the room does to the tone, third of an octave by third of an
 * octave, as the level with the room beside the level without it. Run with ROOM_TONE set.
 */
class SpatialRoomTrial {

    private fun ears(room: Float, third: Boolean, l: Float, r: Float, rate: Int, frames: Int = 8192): Pair<FloatArray, FloatArray> {
        val p = BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = third
            this.room = room
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
            flush()
        }
        val input = ByteBuffer.allocateDirect(8 * frames).order(ByteOrder.nativeOrder())
        for (n in 0 until frames) input.putFloat(if (n == 0) l else 0f).putFloat(if (n == 0) r else 0f)
        input.flip()
        p.queueInput(input)
        val out = p.output
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (n in 0 until frames) { left[n] = out.float; right[n] = out.float }
        return left to right
    }

    /** The energy of [a] in the third of an octave round [centre]. */
    private fun band(a: FloatArray, centre: Double, rate: Int): Double {
        var sum = 0.0
        val points = 15
        for (k in 0 until points) {
            val hz = centre * 2.0.pow((k - (points - 1) / 2.0) / (points - 1) / 3.0)
            val w = 2 * PI * hz / rate
            var re = 0.0
            var im = 0.0
            for (n in a.indices) { re += a[n] * cos(w * n); im -= a[n] * sin(w * n) }
            sum += re * re + im * im
        }
        return sum / points
    }

    @Test
    fun tone() {
        assumeTrue("set ROOM_TONE to run", !System.getenv("ROOM_TONE").isNullOrBlank())
        val centres = listOf(25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0,
            1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0)
        for ((name, l, r) in listOf(Triple("centre", 1f, 1f), Triple("left only", 1f, 0f))) {
            println("$name: the room at 50% and at 100% beside none, dB, left ear then right ear; third order at 48000, at 44100, first order at 48000")
            val sets = listOf(true to 48000, true to 44100, false to 48000).map { (third, rate) ->
                Triple(ears(0f, third, l, r, rate), ears(0.5f, third, l, r, rate), ears(1f, third, l, r, rate)) to rate
            }
            for (hz in centres) {
                val line = StringBuilder("%7.0f Hz ".format(hz))
                for ((set, rate) in sets) {
                    val (open, half, full) = set
                    fun off(a: FloatArray, b: FloatArray) = 10 * log10(band(a, hz, rate) / band(b, hz, rate))
                    line.append("  %+5.1f %+5.1f | %+5.1f %+5.1f  ".format(off(half.first, open.first), off(full.first, open.first), off(half.second, open.second), off(full.second, open.second)))
                }
                println(line)
            }
        }
    }
}
