/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How loud the music is, moment by moment, in three ranges: bass, the middle and the top. It is
 * what the player's living background moves to.
 *
 * Each value runs from 0 to 1, where 1 is as loud as that range has been lately in this song. They
 * are measured against the song itself and not against full scale, so a quiet recording moves the
 * picture as much as a loud one, and turning the volume down does not flatten it (the samples are
 * read before the volume is applied).
 *
 * Nothing here knows about Android or the player: [LevelAnalyser] turns samples into levels,
 * [LevelTimeline] keeps a few seconds of them by time. The samples reach the output a good
 * fraction of a second after they are measured, so a level must be looked up by the time that is
 * being heard, never taken as it arrives.
 */
object MusicLevels {
    const val BASS = 0
    const val MID = 1
    const val HIGH = 2
    const val BANDS = 3

    /** Where the bass ends and where the top begins, in Hz. */
    const val BASS_TOP_HZ = 140.0
    const val HIGH_BOTTOM_HZ = 2500.0

    /** One level every this many seconds. Long enough to hold a whole cycle of a 50 Hz bass note. */
    const val FRAME_SECONDS = 0.02

    /** How long the loudest moment stays the yardstick before a quieter passage becomes it, in seconds. */
    const val YARDSTICK_SECONDS = 8.0

    /**
     * Below this a range counts as silent and is never stretched up to 1: about -50 dB of full
     * scale. Without it the hiss before a song starts would be the loudest thing heard so far.
     */
    const val SILENCE = 0.003f

    /**
     * No range is measured against less than this share of the loudest range's yardstick (-18 dB).
     * A song with almost nothing at the top should show almost nothing there, not its tape hiss
     * at full swing.
     */
    const val LEAST_SHARE = 0.125f
}

/**
 * Turns PCM samples into [MusicLevels], one frame every [MusicLevels.FRAME_SECONDS].
 *
 * Three ranges from six one-pole filters rather than a Fourier transform: about twenty additions
 * and multiplications a sample, which is nothing next to decoding the audio. Every edge is two
 * poles steep, because the levels are measured against themselves: whatever leaks from one range
 * into an emptier one is stretched up with it, and with one pole a bass note alone came out three
 * quarters as loud in the middle range as in its own.
 *
 * Not thread safe: one thread feeds it.
 */
class LevelAnalyser(private val sampleRate: Int, private val onFrame: (endsAtSample: Long, levels: FloatArray) -> Unit) {

    private val frameLength = max(1, (sampleRate * MusicLevels.FRAME_SECONDS).toInt())
    private val bassStep = step(MusicLevels.BASS_TOP_HZ)
    private val highStep = step(MusicLevels.HIGH_BOTTOM_HZ)
    private val yardstickKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.YARDSTICK_SECONDS).toFloat()

    // filter memories, in the order sample() runs them
    private var bass1 = 0f
    private var bass2 = 0f
    private var underRest = 0f
    private var mid1 = 0f
    private var mid2 = 0f
    private var underHigh = 0f

    private val sum = DoubleArray(MusicLevels.BANDS)
    private var inFrame = 0
    private var samplesSeen = 0L

    /** The loudest each range has been lately, as an amplitude. Starts at an ordinary song's, so the first note is not the loudest ever. */
    private val yardstick = floatArrayOf(0.18f, 0.12f, 0.05f)
    private val levels = FloatArray(MusicLevels.BANDS)

    /** How loud each range was in the frame just finished, against full scale. For whoever wants the measure before it is set against the song. */
    val raw = FloatArray(MusicLevels.BANDS)

    private fun step(cutoffHz: Double) = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()

    /** One sample, all channels already mixed into one, full scale being -1 to 1. */
    fun sample(x: Float) {
        // bass: what two low-pass stages let through
        bass1 += bassStep * (x - bass1)
        bass2 += bassStep * (bass1 - bass2)
        // the rest: the same two stages taken away
        val rest1 = x - bass1
        underRest += bassStep * (rest1 - underRest)
        val rest = rest1 - underRest
        // the middle is the low part of the rest, the top is what is left of it
        mid1 += highStep * (rest - mid1)
        mid2 += highStep * (mid1 - mid2)
        val high1 = rest - mid1
        underHigh += highStep * (high1 - underHigh)
        val high = high1 - underHigh

        sum[MusicLevels.BASS] += (bass2 * bass2).toDouble()
        sum[MusicLevels.MID] += (mid2 * mid2).toDouble()
        sum[MusicLevels.HIGH] += (high * high).toDouble()
        samplesSeen++
        if (++inFrame == frameLength) finishFrame()
    }

    private fun finishFrame() {
        var loudest = 0f
        // A float stream can carry a sample that is not a number, and a filter that has taken one
        // in gives nothing else ever after. Such a frame counts as silence and the filters start over.
        if (sum.any { it.isNaN() || it.isInfinite() }) {
            jump()
            raw.fill(0f)
            levels.fill(0f)
            onFrame(samplesSeen, levels)
            return
        }
        for (band in 0 until MusicLevels.BANDS) {
            raw[band] = sqrt(sum[band] / frameLength).toFloat()
            sum[band] = 0.0
            yardstick[band] = max(yardstick[band] * yardstickKeeps, raw[band])
            loudest = max(loudest, yardstick[band])
        }
        // A range with next to nothing in it is not stretched to full: its yardstick is never
        // taken to be less than a small part of the loudest range's.
        val least = max(MusicLevels.SILENCE, loudest * MusicLevels.LEAST_SHARE)
        for (band in 0 until MusicLevels.BANDS) {
            levels[band] = if (raw[band] <= MusicLevels.SILENCE) 0f else (raw[band] / max(yardstick[band], least)).coerceIn(0f, 1f)
        }
        inFrame = 0
        onFrame(samplesSeen, levels)
    }

    /** 16 bit samples from [buffer]'s position to its limit, [channels] interleaved. The buffer is left as it was. */
    fun pcm16(buffer: ByteBuffer, channels: Int) {
        val from = buffer.position()
        val frames = (buffer.limit() - from) / (2 * channels)
        var at = from
        repeat(frames) {
            var mixed = 0
            repeat(channels) {
                mixed += buffer.getShort(at)
                at += 2
            }
            sample(mixed / (32768f * channels))
        }
    }

    /** The same for 32 bit float samples. */
    fun pcmFloat(buffer: ByteBuffer, channels: Int) {
        val from = buffer.position()
        val frames = (buffer.limit() - from) / (4 * channels)
        var at = from
        repeat(frames) {
            var mixed = 0f
            repeat(channels) {
                mixed += buffer.getFloat(at)
                at += 4
            }
            sample(mixed / channels)
        }
    }

    /** The audio jumped (a seek, another song): what is left of a half measured frame is dropped. The yardstick stays. */
    fun jump() {
        sum.fill(0.0)
        inFrame = 0
        bass1 = 0f
        bass2 = 0f
        underRest = 0f
        mid1 = 0f
        mid2 = 0f
        underHigh = 0f
    }
}

/**
 * A few seconds of levels by the time they are heard, written by the thread that plays and read by
 * the one that draws.
 *
 * Times are the player's own (microseconds, as the audio output counts them) and only ever grow
 * between two [clear]s.
 */
class LevelTimeline(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val values = FloatArray(capacity * MusicLevels.BANDS)
    private var next = 0
    private var held = 0

    @Synchronized
    fun add(timeUs: Long, levels: FloatArray) {
        times[next] = timeUs
        System.arraycopy(levels, 0, values, next * MusicLevels.BANDS, MusicLevels.BANDS)
        next = (next + 1) % capacity
        if (held < capacity) held++
    }

    @Synchronized
    fun clear() {
        next = 0
        held = 0
    }

    /**
     * The levels being heard at [timeUs], into [into]. False when nothing is known for that moment:
     * nothing measured yet, or the newest level before it is more than [staleUs] old.
     */
    @Synchronized
    fun read(timeUs: Long, into: FloatArray, staleUs: Long = 250_000): Boolean {
        for (back in 1..held) {
            val at = (next - back + capacity) % capacity
            if (times[at] <= timeUs) {
                if (timeUs - times[at] > staleUs) return false
                System.arraycopy(values, at * MusicLevels.BANDS, into, 0, MusicLevels.BANDS)
                return true
            }
        }
        return false
    }
}
