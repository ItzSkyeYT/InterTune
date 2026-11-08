/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.RenderedIncoming
import com.dd3boh.outertune.mix.RenderedOutgoing
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.abs
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
) {

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
        val guess = aProbeFrom - ((heldStartMs - aStartMs) * rate / 1000.0).roundToLong()
        val at = find(aProbe, held, heldFrames, channels, guess, SEARCH_MS * rate / 1000)
            ?: return Result.failure(IllegalStateException("the song playing is not the music it was rendered from"))
        val shift = aProbeFrom - at
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

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC)
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
    }

    companion object {
        private const val MAGIC = 0x4D584331 // "MXC1"

        /** How far either side of the clock's guess A's music is looked for. */
        private const val SEARCH_MS = 250

        /** How far a song's level may have moved, as a ratio: about half a dB. */
        private const val LEVEL_REACH = 0.06

        /** How far a decoder may round a sample, and how many may stray further (a handful in a second). */
        private const val TOLERANCE = 4

        /**
         * The change [render] made from A's music [a] (frame 0 at [aStartMs] of A) and B's copy [b]
         * from its first frame, for [spec] written as [written], kept with a second of each song.
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
            )
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

        /** The change a kept file was rendered for and whether on parts, read from its head alone; null where it is not one. */
        fun headOf(input: DataInputStream): Pair<String, Boolean>? = try {
            if (input.readInt() != MAGIC) null else input.readUTF() to input.readBoolean()
        } catch (_: IOException) {
            null
        }

        fun read(input: DataInputStream): StoredChange? = try {
            if (input.readInt() != MAGIC) throw IOException("not a kept change")
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
            StoredChange(
                spec, onParts, rate, channels, aStartMs, aBar1, bBar1, anchors, attA, attB, mixFrom, bStart, mixTo,
                outFirst, outSamples, headStart, headStep, headValues, inFirst, inSamples, inEntry, inBeat,
                peakBefore, peakAfter, aProbeFrom, aProbe, bProbeFrom, bProbe,
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
