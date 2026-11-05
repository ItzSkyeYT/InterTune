/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.Lock
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.Spectrum
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The check, in a beat mix, that the incoming song as it reaches the mixer lines up with the
 * opening that was read ahead of time: a few milliseconds off and the two songs' beats flam for
 * the whole blend. Pure, so tested; the controller runs it on its worker.
 */
object AutomixLock {

    /** More than this apart, and the outgoing song fades away. */
    const val LIMIT_MS = 15.0

    /** A correlation under this is not the same music at all. */
    const val MIN_MATCH = 0.5

    /**
     * The incoming song's onsets in [frames] (interleaved, [channels], from its frame [fromFrame])
     * held against those of the same stretch of the [head] read ahead: the lag of the live ones
     * and how well the two match. Both curves come from the same frames: the stretch is begun on a
     * hop of the opening's analysis, so the decimation and the spectral frames fall where the
     * opening's did. Null when the stretch is under a second or runs past what was kept of the
     * opening's decimated copy, which for an opening read before is only its first seconds of music.
     */
    fun measure(head: RegionAnalysis, frames: ShortArray, fromFrame: Long, channels: Int): Lock.Result? {
        val rate = head.sampleRate
        val hop = (Spectrum.HOP * head.decimation).toLong()
        val start = ceil(fromFrame.toDouble() / hop).toLong() * hop
        val skip = (start - fromFrame).toInt()
        val count = frames.size / channels - skip
        if (count < rate) return null
        val from = (start / head.decimation).toInt() - head.guideFrom
        val to = from + count / head.decimation
        if (from < 0 || to > head.guide.size) return null
        val live = Spectrum.of(FrontEnd.of(frames, rate, channels, skip, count)).onset
        val expected = Spectrum.of(head.guide.copyOfRange(from, to), rate.toDouble() / head.decimation, head.decimation).onset
        return Lock.measure(expected, live, rate.toDouble() / head.decimation / Spectrum.HOP)
    }

    /** Whether a measured lock is good enough to go on with the blend. */
    fun holds(r: Lock.Result): Boolean = abs(r.offsetMs) <= LIMIT_MS && r.match >= MIN_MATCH
}
