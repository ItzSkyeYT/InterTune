/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Biquad
import com.dd3boh.outertune.mix.FilterKind
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Shapes
import com.dd3boh.outertune.mix.edit.laneAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A low-pass or a high-pass on one lane, its cutoff set by the plan on every [LaneVoice.GRID]th
 * frame and its coefficients drawn straight between those points, so a sweep never zips. It joins
 * and leaves the lane by a crossfade from the dry sound over [Engine.BYPASS_S], and only runs while
 * it is wanted or fading, so a lane with no filter is untouched to the bit.
 */
internal class SweptFilter(private val kind: FilterKind, private val channels: Int, private val sampleRate: Int) {
    private val section = Biquad(channels)
    private val from = DoubleArray(5)
    private val to = DoubleArray(5)
    private val now = DoubleArray(5)
    private val wetStep = 1.0 / max(1, (Engine.BYPASS_S * sampleRate).roundToInt())
    private var wet = 0.0
    private var wanted = false
    private var lastHz = 0.0

    /** Whether it touches the sound at all. */
    var running = false
        private set

    /**
     * The cutoff at this grid point and the next; null where the plan wants no filter. A filter
     * the next point asks for is started already, so it is in place on the frame its move begins.
     * Started on a lane that is all but silent, it is in at once: there is nothing to cross from,
     * and a lane coming in thin must not let its bass through for the crossfade's length.
     */
    fun target(here: Double?, next: Double?, quiet: Boolean) {
        wanted = here != null || next != null
        val a = here ?: next
        if (a != null) {
            lastHz = clamp(a)
            Biquad.coefficients(kind, lastHz, Q, sampleRate, from)
            Biquad.coefficients(kind, clamp(next ?: a), Q, sampleRate, to)
            if (!running) {
                running = true
                section.reset()
                wet = if (quiet) 1.0 else 0.0
            }
        } else if (running) {
            // Fading back to dry at the cutoff it last had.
            Biquad.coefficients(kind, lastHz, Q, sampleRate, from)
            System.arraycopy(from, 0, to, 0, 5)
        }
    }

    /** Filters one frame in place, [x] of the way from this grid point to the next. */
    fun process(samples: DoubleArray, x: Double) {
        if (!running) return
        wet = if (wanted) minOf(1.0, wet + wetStep) else maxOf(0.0, wet - wetStep)
        for (i in 0 until 5) now[i] = from[i] + (to[i] - from[i]) * x
        section.set(now)
        for (c in 0 until channels) {
            val dry = samples[c]
            val y = section.process(dry, c)
            samples[c] = if (wet >= 1.0) y else dry + (y - dry) * wet
        }
        if (!wanted && wet <= 0.0) {
            running = false
            section.reset()
        }
    }

    private fun clamp(hz: Double): Double = hz.coerceIn(10.0, 0.45 * sampleRate)

    private companion object {
        const val Q = 0.707_106_781_186_547_6
    }
}

/**
 * The echo a lane leaves by: a delay of [delayFrames], fed back at [Shapes.ECHO_FEEDBACK] through a
 * high-pass at [Shapes.ECHO_LOW_HZ] and a low-pass at [Shapes.ECHO_HIGH_HZ] in the loop, so each
 * repeat comes back thinner and darker than the one before, the way a DJ's echo out sounds.
 */
internal class Echo(private val channels: Int, sampleRate: Int, private val delayFrames: Int) {
    private val line = DoubleArray(delayFrames * channels)
    private var at = 0
    private val highPass = Biquad(channels).apply { set(FilterKind.HIGH_PASS, Shapes.ECHO_LOW_HZ, Q, sampleRate) }
    private val lowPass = Biquad(channels).apply { set(FilterKind.LOW_PASS, minOf(Shapes.ECHO_HIGH_HZ, 0.45 * sampleRate), Q, sampleRate) }

    /** Whether anything was ever sent in: until then it is silent and costs nothing. */
    var started = false
        private set

    /** Takes [send] in and adds the echo coming back, at [level], to [out]. */
    fun process(send: DoubleArray, out: DoubleArray, level: Double) {
        if (!started) {
            var any = false
            for (c in 0 until channels) if (send[c] != 0.0) any = true
            if (!any) return
            started = true
        }
        val base = at * channels
        for (c in 0 until channels) {
            val back = lowPass.process(highPass.process(line[base + c], c), c)
            line[base + c] = send[c] + Shapes.ECHO_FEEDBACK * back
            out[c] += back * RETURN * level
        }
        at++
        if (at == delayFrames) at = 0
    }

    companion object {
        private const val Q = 0.707_106_781_186_547_6

        /** The echo comes back 3 dB under the lane, as an echo send on a DJ mixer usually sits. */
        const val RETURN = 0.707_106_781_186_547_6
    }
}

/**
 * One lane of one side as the change plays it: its gain, its filters and its echo, all from
 * [com.dd3boh.outertune.mix.edit.laneAt], the same rule the editor draws from, read on an absolute
 * grid of [GRID] output frames and drawn straight between its points.
 *
 * The gain never moves faster than from full to silence in [Shapes.CUT_MS]: the editor draws a cut
 * as a step, and a step in a lane playing music is a click. Slower moves are followed exactly,
 * and a lane held at full is multiplied by exactly 1.
 */
internal class LaneVoice(private val id: LaneId, private val plan: LanePlan, private val channels: Int) {
    private val rate = plan.facts.sampleRate
    private val lowPass = SweptFilter(FilterKind.LOW_PASS, channels, rate)
    private val highPass = SweptFilter(FilterKind.HIGH_PASS, channels, rate)
    private val echo: Echo? = if (plan.echoes(id)) Echo(channels, rate, plan.echoFrames) else null
    private val slew = 1.0 / max(1.0, Shapes.CUT_MS / 1000.0 * rate)
    private val y = DoubleArray(channels)
    private val send = DoubleArray(channels)

    private var grid = Long.MIN_VALUE
    private var gainHere = 0.0
    private var gainNext = 0.0
    private var sendHere = 0.0
    private var sendNext = 0.0
    private var gain = Double.NaN
    private var sendLevel = 0.0

    /** Whether the lane passes as it is at the frame last moved to: at full, unfiltered, no echo. */
    val isFull: Boolean
        get() = gain == 1.0 && gainHere == 1.0 && gainNext == 1.0 && !lowPass.running && !highPass.running && echo?.started != true

    /** Whether nothing of the lane is heard at the frame last moved to, and nothing is ringing. */
    val isSilent: Boolean
        get() = gain == 0.0 && gainHere == 0.0 && gainNext == 0.0 && echo?.started != true

    private var x = 0.0

    /** Moves the lane to output frame [o]: reads the plan where a grid point is passed, and steps the gain. */
    fun moveTo(o: Long) {
        val offset = Math.floorMod(o, GRID.toLong())
        val g = o - offset
        if (g != grid) load(g)
        x = offset.toDouble() / GRID
        val target = gainHere + (gainNext - gainHere) * x
        gain = if (gain.isNaN()) target else toward(gain, target)
        val sendTarget = sendHere + (sendNext - sendHere) * x
        sendLevel = toward(sendLevel, sendTarget)
    }

    /** Adds the lane's sound at the frame moved to, from its band [band], to [out]. */
    fun process(band: DoubleArray, out: DoubleArray, echoLevel: Double) {
        for (c in 0 until channels) y[c] = band[c]
        lowPass.process(y, x)
        highPass.process(y, x)
        val g = gain
        for (c in 0 until channels) {
            val v = y[c] * g
            out[c] += v
            send[c] = v * sendLevel
        }
        echo?.process(send, out, echoLevel)
    }

    private fun load(g: Long) {
        grid = g
        val here = plan.spec.laneAt(id, plan.u(g.toDouble()))
        val next = plan.spec.laneAt(id, plan.u((g + GRID).toDouble()))
        gainHere = here.gain
        gainNext = next.gain
        sendHere = if (echo != null) here.echoSend else 0.0
        sendNext = if (echo != null) next.echoSend else 0.0
        val quiet = gain.isNaN() && here.gain <= QUIET || !gain.isNaN() && gain <= QUIET
        lowPass.target(here.lowPassHz, next.lowPassHz, quiet)
        highPass.target(here.highPassHz, next.highPassHz, quiet)
    }

    private fun toward(value: Double, target: Double): Double {
        val d = target - value
        return if (abs(d) <= slew) target else value + if (d > 0) slew else -slew
    }

    companion object {
        /** The plan is read on every 32nd output frame, 0.7 ms at 48 kHz, on an absolute grid. */
        const val GRID = 32

        /** A lane this low is all but silent, so a filter starting on it needs no crossfade. */
        private const val QUIET = 0.1
    }
}

/** The engine's own timings, in one place. */
object Engine {
    /** How long a filter takes to join or leave a lane, from or back to its dry sound. */
    const val BYPASS_S = 0.05

    /**
     * Room around where both songs sound, so the shared headroom can bend down before it and come
     * back to exactly 1 after it: more than the 0.29 s its smoothing reaches.
     */
    const val HEADROOM_PAD_S = 0.4

    /** An echo rings out over this many repeats at most, 60 dB down at a feedback of a half. */
    const val ECHO_REPEATS = 10

    /** But never more than this many bars past the change. */
    const val ECHO_MAX_BARS_AFTER = 2.0

    /** The longest a change may take, its echo included. */
    const val MAX_SPAN_S = 64.0

    /** The cosine an echo's last repeat is faded over, as a share of the time it rings. */
    internal fun echoLevel(o: Long, fadeFrom: Long, end: Long): Double = when {
        o < fadeFrom -> 1.0
        o >= end -> 0.0
        else -> cos(PI / 2 * (o - fadeFrom).toDouble() / (end - fadeFrom).toDouble())
    }
}
