/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Pcm
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.auto.AutoInput
import com.dd3boh.outertune.mix.auto.AutoKind
import com.dd3boh.outertune.mix.auto.AutoPick
import com.dd3boh.outertune.mix.auto.AutoPlanner
import com.dd3boh.outertune.mix.auto.Proof
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.max
import kotlin.math.min

/**
 * Auto on lows, mids and highs from the music at hand: the change [AutoPlanner] ranks best for
 * the pair, each candidate in turn rendered by the lane mixer exactly as it would play and judged
 * by its proof, so the one kept is clean as heard and not only as planned. The phone hands it the
 * held end and the next song's copy, the editor its own two windows; both then play or preview
 * the very render that passed.
 */
object AutoOnBands {

    /**
     * One song as it is at hand: [copy] is the music the change is rendered from, [analysis] what
     * was found in the stretch of the song starting at [analysisStartMs] (which may hold more than
     * the copy), [songMs] the whole song's length.
     */
    class Song(
        val id: String,
        val copy: Copy,
        val analysis: RegionAnalysis,
        val analysisStartMs: Double,
        val songMs: Double,
        val album: String? = null,
        val fix: BeatFix? = null,
    ) {
        /** What Auto knows of this end of the song, in ms of its own clock. */
        val end: SongEnd by lazy { SongEnd.of(analysis, analysisStartMs, songMs) }
    }

    class Chosen(
        val pick: AutoPick,
        /** The render of [pick] whose proof passed, ready to hand to the stage or the preview; null when Auto stays plain. */
        val render: ChangeRender?,
        /** How many candidates were rendered and judged. */
        val proofs: Int,
        val millis: Double,
    ) {
        val spec: TransitionSpec get() = pick.spec()
    }

    /**
     * Auto's change from [a]'s end into [b]'s opening. [earliest] is the first frame of [a]'s
     * copy a change may touch, what has gone out already and the time to get it in; [recent] the
     * moves of the last changes, newest first, so the same one does not come three times running.
     * At most [maxProofs] candidates are rendered, each costing a render.
     */
    fun choose(
        a: Song,
        b: Song,
        earliest: Long = 0,
        recent: List<Preset> = emptyList(),
        attenuationA: Double = 1.0,
        attenuationB: Double = 1.0,
        maxProofs: Int = AutoPlanner.MAX_PROOFS,
    ): Chosen {
        val started = System.nanoTime()
        val rate = a.copy.sampleRate
        val earliestMs = a.copy.startMs + earliest * 1000.0 / rate
        val input = AutoInput(
            a = a.end, b = b.end, aId = a.id, bId = b.id, aAlbum = a.album, bAlbum = b.album,
            fixA = a.fix, fixB = b.fix,
            earliestMs = max(earliestMs, a.end.fromMs),
            recent = recent,
        )
        var last: Pair<AutoPick, ChangeRender>? = null
        var proofs = 0
        val renderer = Proof.Renderer { pick ->
            proofs++
            when (val r = render(pick.spec(), a, b, earliest, attenuationA, attenuationB)) {
                is Rendered.Refused -> Proof.refused(rate, r.why)
                is Rendered.Done -> {
                    last = pick to r.render
                    proofOf(r.render, a.copy, b.copy, beatLocked = pick.kind == AutoKind.BEAT_LOCKED)
                }
            }
        }
        val pick = AutoPlanner.pick(input, renderer, maxProofs = maxProofs)
        // A mix handed back is the candidate rendered last, the one whose proof passed (lined up
        // again, if it was, before that last render), so its render is the one to play.
        val render = if (pick.isPlain) null else last?.second
            ?: (render(pick.spec(), a, b, earliest, attenuationA, attenuationB) as? Rendered.Done)?.render
        return Chosen(pick, render, proofs, (System.nanoTime() - started) / 1e6)
    }

    private sealed interface Rendered {
        class Done(val render: ChangeRender) : Rendered
        class Refused(val why: String) : Rendered
    }

    /** [spec] planned on the copies and rendered by the lane mixer, or why it cannot be played on them. */
    private fun render(spec: TransitionSpec, a: Song, b: Song, earliest: Long, attenuationA: Double, attenuationB: Double): Rendered {
        if (a.copy.sampleRate != b.copy.sampleRate || a.copy.channels != b.copy.channels) return Rendered.Refused("the two copies are in different formats")
        val rate = a.copy.sampleRate
        // The stretch lands A's own beats as its analysis heard them, moved onto the copy's frames.
        val shift = (a.copy.startMs - a.analysisStartMs) * rate / 1000.0
        val anchors = a.analysis.takeIf { it.beatsConfident && spec.beatMatched }?.beatFrames()
            ?.let { frames -> if (shift == 0.0) frames else DoubleArray(frames.size) { frames[it] - shift } }
        val facts = ChangeFacts(
            sampleRate = rate,
            channels = a.copy.channels,
            aBar1 = (spec.a.atMs - a.copy.startMs) * rate / 1000.0,
            aFrames = a.copy.frames.toLong(),
            bBar1 = (spec.b.atMs - b.copy.startMs) * rate / 1000.0,
            bFrames = b.copy.frames.toLong(),
            earliest = earliest,
            anchors = anchors?.takeIf { it.isNotEmpty() },
        )
        return when (val r = SpecPlan.plan(spec, facts)) {
            is SpecPlan.Result.Refused -> Rendered.Refused(r.why)
            is SpecPlan.Result.Planned -> Rendered.Done(LaneMixer(r.plan).render(a.copy.pcm, b.copy.pcm, attenuationA, attenuationB))
        }
    }

    /** Seconds of each song's own music either side of the change in a proof, for its 3 s loudness to be measured on. */
    const val PROOF_AROUND_S = 4.0

    /**
     * The proof of a rendered change: the sum as heard, before any limit, from a few seconds before
     * it to a few after; each song as it would sound there on its own; and where both beats are
     * heard together, the two as they meet, for the lock to be measured on.
     */
    fun proofOf(render: ChangeRender, a: Copy, b: Copy, beatLocked: Boolean): Proof {
        val plan = render.plan
        val rate = plan.sampleRate
        val channels = plan.channels
        val around = (PROOF_AROUND_S * rate).toLong()
        val from = max(0L, plan.mixFrom - around)
        val to = min(plan.mixTo + around, plan.bStart + b.frames)
        val n = (to - from).toInt().coerceAtLeast(0)
        val mix = FloatArray(2 * n)
        val plainA = FloatArray(2 * n)
        val plainB = FloatArray(2 * n)
        val stretched = LaneMixer(plan).outgoingSource(a.pcm)
        val raw = PcmSource(a.pcm, channels, a.frames.toLong())
        val bSource = PcmSource(b.pcm, channels, b.frames.toLong(), plan.bStart)
        val fa = DoubleArray(channels)
        val fb = DoubleArray(channels)
        val scale = 1.0 / Pcm.FULL_SCALE
        val aEnd = plan.aMap.outAt(a.frames.toDouble())
        for (i in 0 until n) {
            val o = from + i
            // A on its own: as it is until the change, stretched where the change stretches it.
            if (o < plan.mixFrom) raw.frame(o, fa) else if (o < aEnd) stretched.frame(o, fa) else fa.fill(0.0)
            bSource.frame(o, fb)
            for (c in 0 until 2) {
                val ch = min(c, channels - 1)
                val va = fa[ch] * render.attenuationA
                val vb = fb[ch] * render.attenuationB
                plainA[2 * i + c] = (va * scale).toFloat()
                plainB[2 * i + c] = (vb * scale).toFloat()
                val heard = when {
                    o < plan.mixFrom -> va
                    o >= plan.mixTo -> vb
                    else -> render.outgoing.at(o, ch).toDouble() + render.incoming.at(o, ch).toDouble()
                }
                mix[2 * i + c] = (heard * scale).toFloat()
            }
        }
        val changeFrom = (plan.mixFrom - from).toInt().coerceIn(0, n)
        val changeTo = (min(render.outgoing.endFrame, plan.mixTo) - from).toInt().coerceIn(changeFrom, n)
        if (!beatLocked) return Proof(rate, mix, changeFrom, changeTo, plainA = plainA, plainB = plainB)
        // Where both beats meet: the change itself, A as stretched before its lanes are shaped
        // and B as it plays, on B's beats.
        val lockFrom = (plan.out(0.0).toLong() - from).toInt().coerceIn(0, n)
        val lockTo = (plan.out(plan.spec.lengthBars).toLong() - from).toInt().coerceIn(lockFrom, n)
        val lockA = FloatArray(lockTo - lockFrom) { (plainA[2 * (lockFrom + it)] + plainA[2 * (lockFrom + it) + 1]) / 2 }
        val lockB = FloatArray(lockTo - lockFrom) { (plainB[2 * (lockFrom + it)] + plainB[2 * (lockFrom + it) + 1]) / 2 }
        val spec = plan.spec
        val beatMsB = spec.b.barMs / spec.b.beatsPerBar
        val beats = (0..(spec.lengthBars * spec.b.beatsPerBar).toInt())
            .map { (spec.b.atMs - b.startMs + it * beatMsB) * rate / 1000.0 + plan.bStart - from - lockFrom }
            .filter { it >= 0 && it < lockTo - lockFrom }
            .toDoubleArray()
        return Proof(rate, mix, changeFrom, changeTo, lockA, lockB, lockFrom, beats, plainA, plainB)
    }
}
