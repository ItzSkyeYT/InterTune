/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.RenderedIncoming
import com.dd3boh.outertune.mix.RenderedOutgoing
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * A change rendered once and kept, to be played as it is (his ask of 8 Nov 2026: "plays the
 * transition from storage instead of having to re-render it"). The editor renders the change it
 * saves from its own copies of the two songs; the playlist plays that render where the songs it
 * meets are the music it was rendered from, to the sample, at the same levels, and renders again
 * otherwise. Kept with it: the change as written ([spec], so an edit since is never played from an
 * old render), the anchors and the places it was rendered on, the headroom, and a second of each
 * song's own music ([aProbe], [bProbe]) to find them in what plays. Nothing in it needs the songs'
 * parts any more: a change made on parts plays from here without them.
 *
 * Since 8 Nov 2026 also short stretches of A's music across the change and on to its end
 * ([probes]): after a jump into the change, its start is not in what is held, and A's music is
 * found by one of those instead, so the change plays on from there ([replayFrom]).
 */
class StoredChange(
    val spec: String,
    /** Whether it was rendered on the songs' parts; on bands otherwise (the editor had no parts for them). */
    val onParts: Boolean,
    val rate: Int,
    val channels: Int,
    /** A's music it was rendered from: its frame 0 at [aStartMs] of A. */
    val aStartMs: Double,
    val aBar1: Double,
    val bBar1: Double,
    val anchors: DoubleArray?,
    val attenuationA: Double,
    val attenuationB: Double,
    val mixFrom: Long,
    val bStart: Long,
    val mixTo: Long,
    val outFirst: Long,
    val outSamples: ShortArray,
    val headStart: Long,
    val headStep: Int,
    val headValues: FloatArray,
    val inFirst: Long,
    val inSamples: ShortArray,
    val inEntry: Long,
    val inBeatFrames: Long,
    val peakBefore: Double,
    val peakAfter: Double,
    val aProbeFrom: Long,
    val aProbe: ShortArray,
    val bProbeFrom: Long,
    val bProbe: ShortArray,
    /** A's music every [PROBE_STEP_MS] after the change's start, [PROBE_MS] each, in A's frames as kept; none in a file kept before them. */
    val probes: List<Probe> = emptyList(),
) {

    /** [samples] of A's music from its frame [from], as kept. */
    class Probe(val from: Long, val samples: ShortArray)

    /** Where the next song may come in, for a change joined part-way ([replayFrom]). */
    sealed class EntryRule {
        /** On its frame [frames], which the end was held for: its frames before are skipped already. */
        class Fixed(val frames: Long) : EntryRule()

        /**
         * Wherever the change brings it in: its frames before are skipped from output frame [from]
         * on, [speed] of them for each frame given out, and done [margin] frames before the change
         * is first heard.
         */
        class Open(val from: Long, val speed: Double, val margin: Long) : EntryRule()
    }

    /**
     * The change on the music held, for the stage: [plan] and both songs' parts in the held end's
     * frames. [joinedAt] is the output frame it is joined on after a jump into it, null where it
     * plays from its start; [shift] is how much later in A the kept frames count than the held
     * end's.
     */
    class KeptPlay(
        val plan: MixPlan,
        val outgoing: RenderedOutgoing,
        val incoming: RenderedIncoming,
        val lanePlan: LanePlan,
        val joinedAt: Long?,
        val shift: Long,
        val headroomDb: Double,
        val peakAfter: Double,
    )

    /**
     * This change for the stage, on A's [held] music (its frame 0 near [heldStartMs] of A, the
     * player's clock) and B's [copy] from its first frame: the render as kept, moved to where A's
     * own music is found in [held]. Null where it cannot play as kept, and the change is rendered
     * again: the change was written since ([written] differs), a level moved by more than half a
     * dB, a song is not the music it was rendered from, or the plan worked out on what plays is
     * not the one rendered (it starts before [earliest], or lands elsewhere).
     */
    fun replay(
        written: String, parsed: TransitionSpec,
        held: ShortArray, heldFrames: Long, heldStartMs: Double,
        copy: ShortArray, copyFrames: Int,
        earliest: Long, levelA: Double, levelB: Double,
    ): Result<ChangeRender> {
        if (written != spec) return Result.failure(IllegalStateException("the change was written since it was rendered"))
        if (abs(levelA / attenuationA - 1) > LEVEL_REACH || abs(levelB / attenuationB - 1) > LEVEL_REACH) {
            return Result.failure(IllegalStateException("a song's level moved since it was rendered"))
        }
        val shift = locate(held, heldFrames, heldStartMs)
            ?: return Result.failure(IllegalStateException("the song playing is not the music it was rendered from"))
        if (!same(bProbe, copy, copyFrames, channels, bProbeFrom)) {
            return Result.failure(IllegalStateException("the next song is not the music it was rendered from"))
        }
        val facts = ChangeFacts(
            rate, channels, aBar1 - shift, heldFrames, bBar1, copyFrames.toLong(), earliest,
            anchors = anchors?.let { a -> DoubleArray(a.size) { a[it] - shift } },
            parts = onParts,
        )
        // Planned as it was rendered: on parts where the editor had them for the two (a change saved
        // on bands among them, as the editor opens it), on bands otherwise.
        val rendered = if (onParts) Presets.onParts(parsed) else Presets.onBands(parsed)
        val plan = when (val r = SpecPlan.plan(rendered, facts)) {
            is SpecPlan.Result.Planned -> r.plan
            is SpecPlan.Result.Refused -> return Result.failure(IllegalStateException(r.why))
        }
        if (plan.mixFrom != mixFrom - shift || plan.bStart != bStart - shift || plan.mixTo != mixTo - shift) {
            return Result.failure(IllegalStateException("the change works out elsewhere on the music playing"))
        }
        val headroom = Headroom(headStart - shift, headStep, headValues)
        val outgoing = RenderedOutgoing(outFirst - shift, outSamples.size / channels, channels, outSamples, headroom)
        val incoming = RenderedIncoming(inFirst - shift, inSamples.size / channels, channels, inSamples, copy, attenuationB, inBeatFrames, inEntry)
        return Result.success(ChangeRender(plan, outgoing, incoming, headroom, peakBefore, peakAfter, attenuationA, attenuationB, 0.0))
    }

    /**
     * This change for the stage on A's [held] music and B's [copy], as [replay] finds it, from
     * wherever it can still be heard: whole where its first frame is at or after [earliest];
     * joined part-way otherwise, after a jump into it (his word of 8 Nov 2026: "that's
     * unacceptable that it drops the transition just cuz i jumped through the song"). Joined, A
     * plays as held until the first frame the change can be heard from, then is crossed into the
     * change as rendered over [CROSS_IN_MS]; B comes in where it would have been by then, as
     * [entry] allows, never before the change has been heard from; and the change plays on as
     * rendered from there. Refused where A's music is not found, B would come in after the end of
     * what is held, or the next song's frames before its entry cannot be skipped in time.
     */
    fun replayFrom(
        written: String, parsed: TransitionSpec,
        held: ShortArray, heldFrames: Long, heldStartMs: Double,
        copy: ShortArray, copyFrames: Int,
        earliest: Long, levelA: Double, levelB: Double,
        entry: EntryRule,
        /** The incoming song's first frames handed back for the check that it came in where planned, where it comes in after its first. */
        checkFrames: Long = 0,
    ): Result<KeptPlay> {
        if (written != spec) return Result.failure(IllegalStateException("the change was written since it was rendered"))
        if (abs(levelA / attenuationA - 1) > LEVEL_REACH || abs(levelB / attenuationB - 1) > LEVEL_REACH) {
            return Result.failure(IllegalStateException("a song's level moved since it was rendered"))
        }
        val shift = locate(held, heldFrames, heldStartMs)
            ?: return Result.failure(IllegalStateException("the song playing is not the music it was rendered from"))
        if (!same(bProbe, copy, copyFrames, channels, bProbeFrom)) {
            return Result.failure(IllegalStateException("the next song is not the music it was rendered from"))
        }
        // Planned again where it was rendered, on the frames as kept, where it starts on music it
        // was rendered from even when what is held starts after it: what is held runs from frame
        // [shift] as kept to [shift] + [heldFrames].
        val facts = ChangeFacts(rate, channels, aBar1, shift + heldFrames, bBar1, copyFrames.toLong(), Long.MIN_VALUE, anchors = anchors, parts = onParts)
        val rendered = if (onParts) Presets.onParts(parsed) else Presets.onBands(parsed)
        val lanePlan = when (val r = SpecPlan.plan(rendered, facts)) {
            is SpecPlan.Result.Planned -> r.plan
            is SpecPlan.Result.Refused -> return Result.failure(IllegalStateException(r.why))
        }
        if (lanePlan.mixFrom != mixFrom || lanePlan.bStart != bStart || lanePlan.mixTo != mixTo) {
            return Result.failure(IllegalStateException("the change works out elsewhere on the music playing"))
        }
        val keptHeadroom = Headroom(headStart, headStep, headValues)
        val keptRender = ChangeRender(
            lanePlan,
            RenderedOutgoing(outFirst, outSamples.size / channels, channels, outSamples, keptHeadroom),
            RenderedIncoming(inFirst, inSamples.size / channels, channels, inSamples, copy, attenuationB, inBeatFrames, inEntry),
            keptHeadroom, peakBefore, peakAfter, attenuationA, attenuationB, 0.0,
        )
        // In the held end's frames from here on.
        val whole = keptRender.heldPlan(checkFrames).translated(shift, heldFrames)
        val headroom = Headroom(headStart - shift, headStep, headValues)
        val outgoing = RenderedOutgoing(outFirst - shift, outSamples.size / channels, channels, outSamples, headroom)
        val incoming = RenderedIncoming(inFirst - shift, inSamples.size / channels, channels, inSamples, copy, attenuationB, inBeatFrames, inEntry)
        val first = mixFrom - shift
        val songChange = bStart - shift
        val bEntry = lanePlan.bEntry

        // Whether the next song, in at its frame [e], is skipped to it by output frame [heard].
        fun skipped(heard: Long, e: Long): Boolean = when (entry) {
            is EntryRule.Fixed -> true
            is EntryRule.Open -> e <= 0L || heard >= entry.from + ceil(e / entry.speed).toLong() + entry.margin
        }
        if (entry is EntryRule.Fixed && entry.frames != bEntry) {
            return Result.failure(IllegalStateException("held for the next song from its frame ${entry.frames}, the change brings it in from $bEntry"))
        }
        if (first >= earliest && skipped(first, bEntry)) {
            return Result.success(KeptPlay(whole, outgoing, incoming, lanePlan, null, shift, headroom.deepestDb, peakAfter))
        }

        // Joined part-way: on the first frame from which it can be heard, A crossed into the change
        // before B comes in, which the held end does as it drains.
        val crossIn = max(1, (CROSS_IN_MS * rate / 1000).toInt())
        var join = max(earliest, first)
        if (entry is EntryRule.Open && !skipped(join, bEntry)) join = max(join, entry.from + ceil(bEntry / entry.speed).toLong() + entry.margin)
        var change = songChange
        if (join + crossIn > songChange) {
            if (entry !is EntryRule.Open) return Result.failure(IllegalStateException("the next song would come in at another frame than the one held for"))
            // B comes in where it would have been by then, which is further in the later it is:
            // the skip to it has to be done by the time anything of the change is heard.
            if (entry.speed <= 1.0) return Result.failure(IllegalStateException("the next song cannot be skipped to where it would be"))
            val needed = ceil((entry.from + entry.margin + crossIn + (bEntry - songChange) / entry.speed) / (1.0 - 1.0 / entry.speed)).toLong()
            change = maxOf(join + crossIn, songChange + 1, needed)
            join = change - crossIn
        }
        val enteredAt = bEntry + (change - songChange)
        if (change > heldFrames) return Result.failure(IllegalStateException("the next song would come in after the end of what is held"))
        val check = if (enteredAt > 0L) max(checkFrames, ENTRY_CHECK_FRAMES) else 0L
        val joined = whole.joinedAt(join, change, enteredAt, crossIn, check)
        // B's part from where it comes in, rising over the same few ms: it comes in on music under way.
        val joinedIncoming = if (change == songChange) incoming else incoming.from(change, enteredAt, crossIn)
        return Result.success(KeptPlay(joined, outgoing, joinedIncoming, lanePlan, join, shift, headroom.deepestDb, peakAfter))
    }

    /**
     * Where A's music as kept is in [held] (its frame 0 near [heldStartMs] of A): how much later in
     * A the kept frames count than [held]'s, or null where it is not found. By the second at the
     * change's start, where that is held; after a jump into the change, by the first of [probes]
     * that is surely in what is held, or the next few.
     */
    fun locate(held: ShortArray, heldFrames: Long, heldStartMs: Double): Long? {
        val radius = SEARCH_MS * rate / 1000
        val clock = ((heldStartMs - aStartMs) * rate / 1000.0).roundToLong()
        find(aProbe, held, heldFrames, channels, aProbeFrom - clock, radius)?.let { return aProbeFrom - it }
        // First the stretches the clock puts well inside what is held, then any that may straddle its start.
        val inside = probes.filter { it.from - clock >= radius && it.from - clock + it.samples.size / channels + radius <= heldFrames }
        val edge = probes.filter { it.from - clock in 0 until radius && it.from - clock + it.samples.size / channels <= heldFrames }
        for (p in inside.take(PROBES_TRIED) + edge.take(1)) {
            find(p.samples, held, heldFrames, channels, p.from - clock, radius)?.let { return p.from - it }
        }
        return null
    }

    /**
     * The same change with stretches of A's music across it ([probes]) taken from [held], where
     * the kept frames count [shift] later than [held]'s: for a file kept before they were, once
     * it has been found whole in what plays.
     */
    fun withProbes(held: ShortArray, heldFrames: Long, shift: Long): StoredChange {
        val cut = probesOf(held, channels, rate, mixFrom - shift, heldFrames).map { Probe(it.from + shift, it.samples) }
        return StoredChange(
            spec, onParts, rate, channels, aStartMs, aBar1, bBar1, anchors, attenuationA, attenuationB, mixFrom, bStart, mixTo,
            outFirst, outSamples, headStart, headStep, headValues, inFirst, inSamples, inEntry, inBeatFrames,
            peakBefore, peakAfter, aProbeFrom, aProbe, bProbeFrom, bProbe, cut,
        )
    }

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC_PROBES)
        out.writeUTF(spec.take(60_000))
        out.writeBoolean(onParts)
        out.writeInt(rate); out.writeInt(channels)
        out.writeDouble(aStartMs); out.writeDouble(aBar1); out.writeDouble(bBar1)
        out.writeInt(anchors?.size ?: -1); anchors?.forEach { out.writeDouble(it) }
        out.writeDouble(attenuationA); out.writeDouble(attenuationB)
        out.writeLong(mixFrom); out.writeLong(bStart); out.writeLong(mixTo)
        out.writeLong(outFirst); shorts(out, outSamples)
        out.writeLong(headStart); out.writeInt(headStep); out.writeInt(headValues.size); headValues.forEach { out.writeFloat(it) }
        out.writeLong(inFirst); shorts(out, inSamples); out.writeLong(inEntry); out.writeLong(inBeatFrames)
        out.writeDouble(peakBefore); out.writeDouble(peakAfter)
        out.writeLong(aProbeFrom); shorts(out, aProbe)
        out.writeLong(bProbeFrom); shorts(out, bProbe)
        out.writeInt(probes.size)
        for (p in probes) {
            out.writeLong(p.from)
            shorts(out, p.samples)
        }
    }

    /** What a kept file says of itself in its first bytes: the change it was rendered for, whether on parts, and whether it holds [probes]. */
    class Head(val spec: String, val onParts: Boolean, val probes: Boolean)

    companion object {
        /** "MXC1": a file kept before [probes]. */
        private const val MAGIC = 0x4D584331

        /** "MXC2": with them. */
        private const val MAGIC_PROBES = 0x4D584332

        /**
         * How far either side of the clock's guess A's music is looked for: the clock counts what is
         * heard, and Bluetooth headphones hear it a few hundred ms after the stage held it (8 Nov
         * 2026: on a WH-1000XM5 every kept change missed at 250 ms).
         */
        internal const val SEARCH_MS = 2_000

        /** A stretch of A's music to find it by after a jump into the change, every so often across it. */
        const val PROBE_MS = 200
        const val PROBE_STEP_MS = 1_000

        /** How many stretches are looked for before A's music is taken for other music. */
        private const val PROBES_TRIED = 4

        /** At most this many stretches kept: two and a half minutes of them. */
        private const val PROBES_MAX = 150

        /** Joined part-way, A crosses from its held end into the change as rendered over this long. */
        const val CROSS_IN_MS = 30

        /** The incoming song's first frames handed back for the check where it comes in after its first. */
        private const val ENTRY_CHECK_FRAMES = 8192L

        /** How far a song's level may have moved, as a ratio: about half a dB. */
        private const val LEVEL_REACH = 0.06

        /** How far a decoder may round a sample, and how many may stray further (a handful in a second). */
        private const val TOLERANCE = 4

        /**
         * The change [render] made from A's music [a] (frame 0 at [aStartMs] of A) and B's copy [b]
         * from its first frame, for [spec] written as [written], kept with a second of each song
         * and stretches of A across the change and on to the end of [a].
         */
        fun of(written: String, render: ChangeRender, a: ShortArray, aStartMs: Double, b: ShortArray): StoredChange {
            val plan = render.plan
            val ch = plan.channels
            val rate = plan.sampleRate
            val probe = rate.toLong()
            // Where each song sounds: silence would be found anywhere.
            val aFrom = audibleFrom(a, ch, probe, plan.mixFrom, -probe)
            val bFrom = audibleFrom(b, ch, probe, rate / 2L, probe)
            fun cut(x: ShortArray, from: Long) = x.copyOfRange((from * ch).toInt(), minOf(x.size, ((from + probe) * ch).toInt()))
            return StoredChange(
                spec = written, onParts = plan.spec.lanes == LaneSet.PARTS, rate = rate, channels = ch, aStartMs = aStartMs,
                aBar1 = plan.facts.aBar1, bBar1 = plan.facts.bBar1, anchors = plan.facts.anchors,
                attenuationA = render.attenuationA, attenuationB = render.attenuationB,
                mixFrom = plan.mixFrom, bStart = plan.bStart, mixTo = plan.mixTo,
                outFirst = render.outgoing.firstFrame, outSamples = render.outgoing.samples,
                headStart = render.headroom.start, headStep = render.headroom.step, headValues = render.headroom.values,
                inFirst = render.incoming.firstFrame, inSamples = render.incoming.samples,
                inEntry = render.incoming.entry, inBeatFrames = render.incoming.beatFrames,
                peakBefore = render.peakBefore, peakAfter = render.peakAfter,
                aProbeFrom = aFrom, aProbe = cut(a, aFrom), bProbeFrom = bFrom, bProbe = cut(b, bFrom),
                probes = probesOf(a, ch, rate, plan.mixFrom, minOf(plan.facts.aFrames, (a.size / ch).toLong())),
            )
        }

        /**
         * Stretches of [music] (its first [frames]) to find it by, every [PROBE_STEP_MS] from a
         * step after [from] to its end, where it sounds.
         */
        private fun probesOf(music: ShortArray, channels: Int, rate: Int, from: Long, frames: Long): List<Probe> {
            val step = rate.toLong() * PROBE_STEP_MS / 1000
            val length = rate.toLong() * PROBE_MS / 1000
            val last = minOf(frames, (music.size / channels).toLong()) - length
            val out = ArrayList<Probe>()
            var at = max(0L, from + step)
            while (at <= last && out.size < PROBES_MAX) {
                if (meanLevel(music, channels, at, length) >= AUDIBLE) {
                    out += Probe(at, music.copyOfRange((at * channels).toInt(), ((at + length) * channels).toInt()))
                }
                at += step
            }
            return out
        }

        /**
         * Where a second of [music] to find it by starts: [from] where the music sounds there, else
         * the first second that does, [step] by [step], ten at most; the loudest of those where none does.
         */
        private fun audibleFrom(music: ShortArray, channels: Int, probe: Long, from: Long, step: Long): Long {
            val last = maxOf(0L, music.size / channels - probe)
            var best = from.coerceIn(0, last)
            var bestLevel = -1.0
            for (i in 0..10) {
                val at = (from + i * step).coerceIn(0, last)
                val level = meanLevel(music, channels, at, probe)
                if (level >= AUDIBLE) return at
                if (level > bestLevel) {
                    best = at
                    bestLevel = level
                }
                if (at == 0L || at == last) break
            }
            return best
        }

        private fun meanLevel(music: ShortArray, channels: Int, from: Long, frames: Long): Double {
            val start = (from * channels).toInt()
            val end = minOf(music.size, ((from + frames) * channels).toInt())
            if (end <= start) return 0.0
            var sum = 0.0
            for (i in start until end) sum += abs(music[i].toInt())
            return sum / (end - start)
        }

        /** A mean sample this loud or louder is music to look for: about -45 dB of full scale. */
        private const val AUDIBLE = 180.0

        /** What a kept file says of itself, read from its head alone; null where it is not one. */
        fun headOf(input: DataInputStream): Head? = try {
            when (input.readInt()) {
                MAGIC -> Head(input.readUTF(), input.readBoolean(), probes = false)
                MAGIC_PROBES -> Head(input.readUTF(), input.readBoolean(), probes = true)
                else -> null
            }
        } catch (_: IOException) {
            null
        }

        fun read(input: DataInputStream): StoredChange? = try {
            val magic = input.readInt()
            if (magic != MAGIC && magic != MAGIC_PROBES) throw IOException("not a kept change")
            val spec = input.readUTF()
            val onParts = input.readBoolean()
            val rate = input.readInt(); val channels = input.readInt()
            val aStartMs = input.readDouble(); val aBar1 = input.readDouble(); val bBar1 = input.readDouble()
            val n = input.readInt()
            val anchors = if (n < 0) null else DoubleArray(n) { input.readDouble() }
            val attA = input.readDouble(); val attB = input.readDouble()
            val mixFrom = input.readLong(); val bStart = input.readLong(); val mixTo = input.readLong()
            val outFirst = input.readLong(); val outSamples = shorts(input)
            val headStart = input.readLong(); val headStep = input.readInt()
            val headValues = FloatArray(input.readInt()) { input.readFloat() }
            val inFirst = input.readLong(); val inSamples = shorts(input); val inEntry = input.readLong(); val inBeat = input.readLong()
            val peakBefore = input.readDouble(); val peakAfter = input.readDouble()
            val aProbeFrom = input.readLong(); val aProbe = shorts(input)
            val bProbeFrom = input.readLong(); val bProbe = shorts(input)
            val probes = if (magic != MAGIC_PROBES) emptyList() else {
                val count = input.readInt()
                if (count !in 0..PROBES_MAX) throw IOException("$count stretches")
                List(count) { Probe(input.readLong(), shorts(input)) }
            }
            StoredChange(
                spec, onParts, rate, channels, aStartMs, aBar1, bBar1, anchors, attA, attB, mixFrom, bStart, mixTo,
                outFirst, outSamples, headStart, headStep, headValues, inFirst, inSamples, inEntry, inBeat,
                peakBefore, peakAfter, aProbeFrom, aProbe, bProbeFrom, bProbe, probes,
            )
        } catch (_: IOException) {
            null
        }

        private fun shorts(out: DataOutputStream, x: ShortArray) {
            out.writeInt(x.size)
            val bytes = ByteArray(x.size * 2)
            for (i in x.indices) {
                bytes[2 * i] = (x[i].toInt() and 0xFF).toByte()
                bytes[2 * i + 1] = (x[i].toInt() shr 8).toByte()
            }
            out.write(bytes)
        }

        private fun shorts(input: DataInputStream): ShortArray {
            val n = input.readInt()
            if (n !in 0..200_000_000) throw IOException("$n samples")
            val bytes = ByteArray(n * 2)
            input.readFully(bytes)
            return ShortArray(n) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
        }

        /** The frame of [music] where [probe] starts, within [radius] frames of [guess]; null where no frame matches. */
        internal fun find(probe: ShortArray, music: ShortArray, musicFrames: Long, channels: Int, guess: Long, radius: Int): Long? {
            val frames = probe.size / channels
            if (frames == 0) return null
            for (d in 0..radius) for (sign in if (d == 0) intArrayOf(1) else intArrayOf(1, -1)) {
                val at = guess + sign * d
                if (at < 0 || at + frames > musicFrames) continue
                // Most offsets are told apart by their first samples.
                var ok = true
                for (i in 0 until minOf(probe.size, 256)) if (abs(music[(at * channels).toInt() + i] - probe[i]) > TOLERANCE) { ok = false; break }
                if (ok && same(probe, music, musicFrames.toInt(), channels, at)) return at
            }
            return null
        }

        /** Whether [music] from frame [from] is [probe], but for a decoder's rounding. */
        internal fun same(probe: ShortArray, music: ShortArray, musicFrames: Int, channels: Int, from: Long): Boolean {
            if (from < 0 || from + probe.size / channels > musicFrames) return false
            var off = 0
            val base = (from * channels).toInt()
            for (i in probe.indices) if (abs(music[base + i] - probe[i]) > TOLERANCE) off++
            return off <= probe.size / 10_000
        }
    }
}
