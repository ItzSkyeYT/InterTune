/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.cast.PcmSound
import com.dd3boh.outertune.cast.PcmStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The phone's equalizer and loudness normalisation on the raw audio it casts (cast.PcmSound): the
 * curve [EqualizerAudioProcessor] plays through on the phone, worked out by the same arithmetic,
 * and the level [Normalization] gives each song.
 *
 * The phone's player stops while it casts, and its audio chain with it, so a receiver given raw
 * audio heard neither: an AirPlay speaker played every song flat and unlevelled while Signal path
 * said both were on (a Zeppelin Wireless, 9 Nov 2026).
 *
 * As on the phone, a boost turns the rest down by the curve's headroom rather than anything up,
 * and flat, off and with nothing to level, the samples pass untouched, bit for bit. The curve is
 * read once a buffer, so a band moved is in the next one. A change glides from the old filters to
 * the new over that buffer, carrying their memory, so a drag along a slider does not click; going
 * back to flat, the filters run a quarter of a second longer for their memory to die away. No
 * buffer allocates anything, a change of curve included.
 *
 * Normalisation is each song's own, from its stored loudness: looked up once per song, off the
 * threads that send ([prepare]), with the switch read live.
 */
class CastSound(
    /** The equalizer as set, read once a buffer: in the app, the phone's own processor's. */
    private val equalizer: () -> EqualizerAudioProcessor.Settings,
    /** Whether normalisation is on, read once a buffer. */
    private val normalize: () -> Boolean,
    /** A song's stored loudness and whether it is a file on the phone. Blocking. */
    private val loudness: (mediaId: String) -> Loudness,
) : PcmSound {
    /** What a song's level is worked out from: its stored loudness in dB, null when none, and whether it is a file on the phone. */
    data class Loudness(val db: Double?, val local: Boolean)

    private val songs = ConcurrentHashMap<String, Loudness>()

    override fun prepare(mediaId: String) {
        songs[mediaId] = loudness(mediaId)
    }

    /**
     * Song [mediaId]'s level as set now, by the phone's rule: 1 or less. A song never prepared counts
     * as one of unknown loudness, as on the phone before its row is written.
     */
    fun level(mediaId: String): Float {
        val song = songs[mediaId]
        return Normalization.factor(song?.db, song?.local == true, normalize())
    }

    override fun stage(rate: Int, channels: Int): PcmStage = Stage(rate, channels)

    private inner class Stage(private val rate: Int, private val channels: Int) : PcmStage {
        /** The bands this rate carries: one too near its top is left out, as on the phone. */
        private val bands = EqualizerAudioProcessor.BANDS.count { EqualizerAudioProcessor.Eq.inRange(it, rate.toDouble()) }

        /** Every band at nought: filters that give back exactly what they are given while they hold no memory. */
        private val flat = DoubleArray(bands * 5).also { flat ->
            for (band in 0 until bands) EqualizerAudioProcessor.Eq.peakingInto(flat, band * 5, EqualizerAudioProcessor.BANDS[band], 0.0, EqualizerAudioProcessor.Eq.Q, rate.toDouble())
        }

        /** The filters in use, five coefficients a band; those a change glides to; and the ones in between. */
        private val now = DoubleArray(bands * 5)
        private val next = DoubleArray(bands * 5)
        private val between = DoubleArray(bands * 5)

        /** Two values of memory per band per channel, for the transposed direct form II. */
        private val memory = DoubleArray(bands * channels * 2)

        /** The settings the filters were last worked out for, told apart by identity: the app sets a new one for each change. */
        private var built: EqualizerAudioProcessor.Settings? = null

        /** Whether the filters run: a curve is set, or one went flat a moment ago and their memory is dying away. */
        private var filtering = false

        /** Frames left before the samples pass by the filters again, once the curve went flat. */
        private var fading = 0

        /** What the curve turns everything down by, so that its highest point is where the sound was. */
        private var headroom = 1.0

        /** The gain in use, the headroom times the song's level; NaN at the start, when there is nothing to glide from. */
        private var gain = Double.NaN

        /** The level last worked out, and what it was worked out from: worked out again only when one of them changes. */
        private var songLevel = 1.0
        private var levelOf: String? = null
        private var levelFrom: Loudness? = null
        private var levelOn = false

        override fun shape(mediaId: String, samples: ShortArray, from: Int, out: ShortArray, to: Int, frames: Int) {
            if (frames <= 0) return
            val wanted = equalizer()
            var glide = wanted !== built && retune(wanted)
            val target = headroom * levelNow(mediaId)
            val start = if (gain.isNaN()) {
                // Nothing came before to carry on from: the new filters straight away.
                if (glide) System.arraycopy(next, 0, now, 0, now.size)
                glide = false
                target
            } else gain
            gain = target
            when {
                filtering -> filter(samples, from, out, to, frames, start, target, glide)
                start == 1.0 && target == 1.0 -> if (samples !== out || from != to) System.arraycopy(samples, from, out, to, frames * channels)
                else -> scale(samples, from, out, to, frames, start, target)
            }
            if (fading > 0) {
                fading -= frames
                if (fading <= 0) {
                    fading = 0
                    filtering = false
                    memory.fill(0.0)
                }
            }
        }

        /** The level of song [mediaId] as set now, without working it out again for every buffer. */
        private fun levelNow(mediaId: String): Double {
            val song = songs[mediaId]
            val on = normalize()
            if (mediaId != levelOf || song !== levelFrom || on != levelOn || levelOf == null) {
                songLevel = Normalization.factor(song?.db, song?.local == true, on).toDouble()
                levelOf = mediaId
                levelFrom = song
                levelOn = on
            }
            return songLevel
        }

        /** The filters for [wanted] into [next], and its headroom; true when the filters running glide to them. */
        private fun retune(wanted: EqualizerAudioProcessor.Settings): Boolean {
            built = wanted
            val gains = wanted.gainsDb
            var shaped = false
            if (wanted.enabled) for (band in 0 until bands) if (abs(gains[band]) >= FLAT_DB) shaped = true
            if (!shaped) {
                headroom = 1.0
                if (!filtering || fading > 0) return false
                System.arraycopy(flat, 0, next, 0, next.size)
                // The lowest band's memory falls below a sample's worth in about 160 ms at any rate.
                fading = maxOf(1, rate / 4)
                return true
            }
            for (band in 0 until bands) {
                EqualizerAudioProcessor.Eq.peakingInto(next, band * 5, EqualizerAudioProcessor.BANDS[band], gains[band].toDouble(), EqualizerAudioProcessor.Eq.Q, rate.toDouble())
            }
            headroom = EqualizerAudioProcessor.Eq.dbToGain(-EqualizerAudioProcessor.Eq.headroomDb(next, bands, rate.toDouble()))
            if (!filtering) {
                // From the samples as they are, which flat filters with no memory give exactly.
                System.arraycopy(flat, 0, now, 0, now.size)
                memory.fill(0.0)
                filtering = true
            }
            fading = 0
            return true
        }

        private fun filter(samples: ShortArray, from: Int, out: ShortArray, to: Int, frames: Int, start: Double, end: Double, glide: Boolean) {
            val step = stepOf(start, end, frames)
            val coefficients = if (glide) between else now
            var g = start
            var i = from
            var o = to
            for (frame in 0 until frames) {
                g *= step
                if (glide) {
                    // Each coefficient a straight line from the old filter to the new: every point of
                    // it a stable filter, since the stable ones form a convex set.
                    val t = (frame + 1).toDouble() / frames
                    for (k in between.indices) between[k] = now[k] + (next[k] - now[k]) * t
                }
                var m = 0
                for (channel in 0 until channels) {
                    var x = samples[i++] * g
                    for (band in 0 until bands) {
                        val k = band * 5
                        val y = coefficients[k] * x + memory[m]
                        memory[m] = coefficients[k + 1] * x - coefficients[k + 3] * y + memory[m + 1]
                        memory[m + 1] = coefficients[k + 2] * x - coefficients[k + 4] * y
                        x = y
                        m += 2
                    }
                    out[o++] = clamp(x)
                }
            }
            if (glide) System.arraycopy(next, 0, now, 0, now.size)
        }

        private fun scale(samples: ShortArray, from: Int, out: ShortArray, to: Int, frames: Int, start: Double, end: Double) {
            val step = stepOf(start, end, frames)
            var g = start
            var i = from
            var o = to
            for (frame in 0 until frames) {
                g *= step
                for (channel in 0 until channels) out[o++] = clamp(samples[i++] * g)
            }
        }

        /**
         * What the gain is multiplied by each frame to go from [start] to [end] in [frames]: a glide
         * even in dB, as the filters' own glide nearly is, so that a band raised while the rest is
         * turned down by as much does not swell on the way.
         */
        private fun stepOf(start: Double, end: Double, frames: Int): Double =
            if (start == end) 1.0 else (end / start).pow(1.0 / frames)

        override fun reset() {
            memory.fill(0.0)
            gain = Double.NaN
            if (fading > 0) {
                fading = 0
                filtering = false
            }
        }
    }

    private companion object {
        /** A band this close to nought counts as flat, as EqualizerAudioProcessor.Settings.isFlat has it. */
        const val FLAT_DB = 0.05f

        /** Rounded, not cut: truncation would pull every sample towards nought, which is distortion. */
        fun clamp(value: Double): Short = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
