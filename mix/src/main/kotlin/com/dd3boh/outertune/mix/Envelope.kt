/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a region's sound starts and ends, and how its level moves in between. Every position is
 * a full-rate frame from the start of the region.
 *
 * Built from the front end's hops, each [FrontEndResult.hopFrames] frames long (11.6 ms at 44.1
 * kHz), so every answer here is good to about a hop, which is all a blend between two songs asks.
 */
class Envelope(
    val sampleRate: Int,
    val hopFrames: Int,
    /** The region's length. */
    val frames: Long,
    /** Where the level (50 ms RMS) first rises above -50 dBFS. */
    val firstSound: Long,
    /** Where the level (400 ms RMS) falls below -50 dBFS for good. */
    val trueEnd: Long,
    /** From where the level stays 6 dB under the median to the true end; the true end if it never does. */
    val outroStart: Long,
    /** The first rise of 6 dB that holds for a bar after the first sound, or null if there is none. */
    val introEnd: Long?,
    /** The median of the one-second level between first sound and true end, in dBFS. */
    val medianDb: Double,
    /** How long the fade that ends the region is, or null if it does not end in a fade. */
    val fadeFrames: Long?,
    /** The one-second level around each hop, in dBFS. */
    val levelDb: FloatArray,
) {
    val seconds: Double get() = frames.toDouble() / sampleRate

    fun secondsOf(frame: Long): Double = frame.toDouble() / sampleRate

    /** The hop that holds [frame]. */
    fun hopOf(frame: Long): Int = (frame / hopFrames).toInt().coerceIn(0, max(0, levelDb.size - 1))

    /**
     * Whether the level between [from] and [to] stays with the region's: its median one-second
     * level within 3 dB of the region's median, and its quietest quarter within 6 dB. A loud
     * stretch, where a filter sweep is heard as a sweep and not as a quiet passage getting quieter.
     * The quartile lets a vocal break or a missing kick through, and stops at a fade, whose last
     * seconds sit far under the rest.
     */
    fun staysLoud(from: Long, to: Long): Boolean {
        val lo = hopOf(from)
        val hi = hopOf(to)
        if (hi <= lo) return false
        val levels = (lo until hi).map { levelDb[it] }.sorted()
        return levels[levels.size / 2] >= medianDb - LOUD_DB && levels[levels.size / 4] >= medianDb - 2 * LOUD_DB
    }

    /** The mean one-second level between [from] and [to], in dBFS. */
    fun meanDb(from: Long, to: Long): Double {
        val lo = hopOf(from)
        val hi = max(lo + 1, hopOf(to))
        var sum = 0.0
        for (h in lo until min(hi, levelDb.size)) sum += levelDb[h]
        return sum / max(1, min(hi, levelDb.size) - lo)
    }

    companion object {
        const val SOUND_DB = -50.0
        private const val OUTRO_DB = 6.0
        private const val RISE_DB = 6.0
        private const val LOUD_DB = 3.0
        private const val FADE_DB = 12.0

        /**
         * The envelope of [front]. [barSeconds] is how long a rise must hold to count as the end of
         * the intro; with no beats known, two seconds.
         */
        fun of(front: FrontEndResult, barSeconds: Double = 2.0): Envelope {
            val power = front.hopPower
            val hop = front.hopFrames
            val rate = front.sampleRate
            val n = power.size
            val threshold = Math.pow(10.0, SOUND_DB / 10)
            val prefix = DoubleArray(n + 1)
            for (i in 0 until n) prefix[i + 1] = prefix[i] + power[i]
            fun mean(from: Int, to: Int): Double {
                val lo = from.coerceIn(0, n)
                val hi = to.coerceIn(lo, n)
                return if (hi == lo) 0.0 else (prefix[hi] - prefix[lo]) / (hi - lo)
            }
            val hops50 = max(1, (0.05 * rate / hop).roundToInt())
            val hops400 = max(1, (0.4 * rate / hop).roundToInt())

            // First sound: the first 50 ms that reach -50 dBFS, then back to where that sound began.
            var first = n
            for (h in 0 until n) {
                if (mean(h - hops50 + 1, h + 1) >= threshold) {
                    var start = max(0, h - hops50 + 1)
                    while (start < h && power[start] < threshold * 0.01) start++
                    first = start
                    break
                }
            }

            // True end: the last 400 ms that still reach -50 dBFS, then the last hop in it that
            // does. A window that looks ahead, so a song that stops dead ends where it stops and
            // not 200 ms after, and a lone click after a long silence is not mistaken for music.
            var end = first
            for (h in n - 1 downTo first) {
                if (mean(h, h + hops400) >= threshold) {
                    var last = min(n - 1, h + hops400 - 1)
                    while (last > h && power[last] < threshold) last--
                    end = last + 1
                    break
                }
            }
            val frames = front.frames
            val firstSound = min(frames, first.toLong() * hop)
            val trueEnd = min(frames, end.toLong() * hop).coerceAtLeast(firstSound)

            val half = max(1, (0.5 * rate / hop).roundToInt())
            val level = FloatArray(n) { h -> Pcm.powerDb(mean(h - half, h + half + 1)).toFloat() }
            val endHop = end
            val sounding = (first until endHop).map { level[it] }.sorted()
            val median = if (sounding.isEmpty()) -200.0 else sounding[sounding.size / 2].toDouble()

            // The outro: the last stretch that stays 6 dB down. The last half second is left out,
            // since its one-second window already reaches past the end into silence.
            val lastJudged = max(first, endHop - half)
            var outro = endHop
            for (h in lastJudged - 1 downTo first) {
                if (level[h] < median - OUTRO_DB) outro = h else break
            }
            val outroStart = if (outro >= lastJudged) trueEnd else min(trueEnd, outro.toLong() * hop)

            // The intro's end: the first rise of 6 dB over the intro's own level that holds for a
            // bar. The intro's level is that of its first two seconds.
            val introRef = (first until min(endHop, first + 4 * half)).map { level[it] }.sorted()
            var introEnd: Long? = null
            if (introRef.isNotEmpty()) {
                val reference = introRef[introRef.size / 2]
                val hold = max(1, (barSeconds * rate / hop).roundToInt())
                var h = first + 2 * half
                while (h + hold <= lastJudged) {
                    if ((h until h + hold).all { level[it] >= reference + RISE_DB }) {
                        introEnd = h.toLong() * hop
                        break
                    }
                    h++
                }
            }

            return Envelope(
                sampleRate = rate,
                hopFrames = hop,
                frames = frames,
                firstSound = firstSound,
                trueEnd = trueEnd,
                outroStart = outroStart,
                introEnd = introEnd,
                medianDb = median,
                fadeFrames = fade(level, first, endHop, half, median)?.let { it.toLong() * hop },
                levelDb = level,
            )
        }

        /**
         * The length, in hops, of the fade that ends the region, if it ends in one: the level last
         * stood within 3 dB of the median at least three seconds before the true end, falls by 12
         * dB or more from there, and keeps falling all the way: each third of the way down at
         * least 3 dB under the third before, so a step down into a quiet outro is not taken for a
         * fader being pulled.
         */
        private fun fade(level: FloatArray, first: Int, end: Int, half: Int, median: Double): Int? {
            val lastJudged = end - half
            if (lastJudged - first < 6 * half) return null
            var from = -1
            for (h in lastJudged - 1 downTo first) {
                if (level[h] >= median - LOUD_DB) {
                    from = h
                    break
                }
            }
            if (from < 0) return null
            val length = end - from
            if (length < 6 * half) return null
            if (level[from] - level[lastJudged - 1] < FADE_DB) return null
            val third = (lastJudged - from) / 3
            if (third < 1) return null
            fun meanOf(a: Int, b: Int): Double {
                var sum = 0.0
                for (h in a until b) sum += level[h]
                return sum / (b - a)
            }
            val one = meanOf(from, from + third)
            val two = meanOf(from + third, from + 2 * third)
            val three = meanOf(from + 2 * third, lastJudged)
            if (one - two < LOUD_DB || two - three < LOUD_DB) return null
            return length
        }
    }
}
