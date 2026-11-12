/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [RegionAnalysis] as bytes, for the Mix editor, which keeps what it found of a song's end or
 * opening beside the audio it was found in, so going back to a pair does not analyse it again (his
 * ask of 6 Nov 2026). Every value the constructors take is written and nothing derived, so a
 * region read back is the one written. [VERSION] goes up whenever one of the classes written here
 * changes; a file of another version is refused and the region is analysed again.
 */
object RegionFile {
    const val VERSION = 1

    /** "MXRA": a region analysis. */
    private const val MAGIC = 0x4d585241

    fun write(r: RegionAnalysis, out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(r.kind.name)
        out.writeInt(r.sampleRate)
        out.writeInt(r.channels)
        out.writeLong(r.frames)
        envelope(r.envelope, out)
        rhythm(r.rhythm, out)
        maybe(r.phrases, out) { phrases(it, out) }
        floats(r.onset, out)
        floats(r.hopPeak, out)
        out.writeInt(r.hopFrames)
        floats(r.guide, out)
        out.writeInt(r.decimation)
        out.writeDouble(r.analysisMillis)
        out.writeInt(r.guideFrom)
        maybe(r.hopPeakAbove120, out) { floats(it, out) }
        maybe(r.hopPeakAbove400, out) { floats(it, out) }
    }

    fun read(input: DataInputStream): RegionAnalysis {
        if (input.readInt() != MAGIC) throw IOException("not a region analysis")
        val version = input.readInt()
        if (version != VERSION) throw IOException("a region analysis of version $version, not $VERSION")
        val kind = RegionKind.valueOf(input.readUTF())
        val sampleRate = input.readInt()
        val channels = input.readInt()
        val frames = input.readLong()
        val envelope = envelope(input)
        val rhythm = rhythm(input)
        val phrases = maybe(input) { phrases(input) }
        val onset = floats(input)
        val hopPeak = floats(input)
        val hopFrames = input.readInt()
        val guide = floats(input)
        val decimation = input.readInt()
        val analysisMillis = input.readDouble()
        val guideFrom = input.readInt()
        val above120 = maybe(input) { floats(input) }
        val above400 = maybe(input) { floats(input) }
        return RegionAnalysis(
            kind = kind, sampleRate = sampleRate, channels = channels, frames = frames, envelope = envelope,
            rhythm = rhythm, phrases = phrases, onset = onset, hopPeak = hopPeak, hopFrames = hopFrames, guide = guide,
            decimation = decimation, analysisMillis = analysisMillis, guideFrom = guideFrom,
            hopPeakAbove120 = above120, hopPeakAbove400 = above400,
        )
    }

    private fun envelope(e: Envelope, out: DataOutputStream) {
        out.writeInt(e.sampleRate)
        out.writeInt(e.hopFrames)
        out.writeLong(e.frames)
        out.writeLong(e.firstSound)
        out.writeLong(e.trueEnd)
        out.writeLong(e.outroStart)
        maybe(e.introEnd, out) { out.writeLong(it) }
        out.writeDouble(e.medianDb)
        maybe(e.fadeFrames, out) { out.writeLong(it) }
        floats(e.levelDb, out)
    }

    private fun envelope(input: DataInputStream): Envelope {
        val sampleRate = input.readInt()
        val hopFrames = input.readInt()
        val frames = input.readLong()
        val firstSound = input.readLong()
        val trueEnd = input.readLong()
        val outroStart = input.readLong()
        val introEnd = maybe(input) { input.readLong() }
        val medianDb = input.readDouble()
        val fadeFrames = maybe(input) { input.readLong() }
        val levelDb = floats(input)
        return Envelope(sampleRate, hopFrames, frames, firstSound, trueEnd, outroStart, introEnd, medianDb, fadeFrames, levelDb)
    }

    private fun rhythm(r: Rhythm, out: DataOutputStream) {
        maybe(r.tempo, out) { t ->
            out.writeDouble(t.bpm)
            out.writeDouble(t.periodFrames)
            out.writeDouble(t.z)
            out.writeDouble(t.rivalRatio)
            out.writeDouble(t.rawBpm)
        }
        maybe(r.grid, out) { g ->
            out.writeDouble(g.t0)
            out.writeDouble(g.period)
            out.writeInt(g.firstBeat)
            out.writeInt(g.lastBeat)
            out.writeDouble(g.hitShare)
            out.writeDouble(g.jitterMs)
            out.writeDouble(g.steadiness)
            out.writeDouble(g.drift)
            doubles(g.beats, out)
            out.writeDouble(g.frameRate)
            ints(g.heardK, out)
            doubles(g.heardT, out)
        }
        maybe(r.downbeats, out) { d ->
            out.writeInt(d.phase)
            out.writeDouble(d.margin)
            doubles(d.scores, out)
            out.writeInt(d.beatsPerBar)
            out.writeDouble(d.halfMargin)
            out.writeBoolean(d.fromFirstSound)
        }
        val g = r.gates
        out.writeDouble(g.tempoZ)
        out.writeDouble(g.rivalRatio)
        out.writeDouble(g.steadiness)
        out.writeDouble(g.drift)
        out.writeDouble(g.hitShare)
        out.writeDouble(g.jitterMs)
        out.writeInt(g.activeBeats)
        out.writeDouble(g.downbeatMargin)
        out.writeBoolean(g.barsFromFirstSound)
        out.writeBoolean(g.byHand)
        out.writeBoolean(g.fromSong)
    }

    private fun rhythm(input: DataInputStream): Rhythm {
        val tempo = maybe(input) {
            val bpm = input.readDouble()
            val periodFrames = input.readDouble()
            val z = input.readDouble()
            val rivalRatio = input.readDouble()
            val rawBpm = input.readDouble()
            TempoEstimate(bpm, periodFrames, z, rivalRatio, rawBpm)
        }
        val grid = maybe(input) {
            val t0 = input.readDouble()
            val period = input.readDouble()
            val firstBeat = input.readInt()
            val lastBeat = input.readInt()
            val hitShare = input.readDouble()
            val jitterMs = input.readDouble()
            val steadiness = input.readDouble()
            val drift = input.readDouble()
            val beats = doubles(input)
            val frameRate = input.readDouble()
            val heardK = ints(input)
            val heardT = doubles(input)
            BeatGrid(t0, period, firstBeat, lastBeat, hitShare, jitterMs, steadiness, drift, beats, frameRate, heardK, heardT)
        }
        val downbeats = maybe(input) {
            val phase = input.readInt()
            val margin = input.readDouble()
            val scores = doubles(input)
            val beatsPerBar = input.readInt()
            val halfMargin = input.readDouble()
            val fromFirstSound = input.readBoolean()
            Downbeats(phase, margin, scores, beatsPerBar, halfMargin, fromFirstSound)
        }
        val tempoZ = input.readDouble()
        val rivalRatio = input.readDouble()
        val steadiness = input.readDouble()
        val drift = input.readDouble()
        val hitShare = input.readDouble()
        val jitterMs = input.readDouble()
        val activeBeats = input.readInt()
        val downbeatMargin = input.readDouble()
        val barsFromFirstSound = input.readBoolean()
        val byHand = input.readBoolean()
        val fromSong = input.readBoolean()
        val gates = Gates(tempoZ, rivalRatio, steadiness, drift, hitShare, jitterMs, activeBeats, downbeatMargin, barsFromFirstSound, byHand, fromSong)
        return Rhythm(tempo, grid, downbeats, gates)
    }

    private fun phrases(p: Phrases, out: DataOutputStream) {
        doubles(p.barLines, out)
        ints(p.barBeats, out)
        doubles(p.novelty, out)
        doubles(p.barDb, out)
        out.writeInt(p.lastStrong)
        out.writeInt(p.candidates.size)
        for (c in p.candidates) {
            out.writeInt(c.line)
            out.writeInt(c.step)
            out.writeDouble(c.score)
        }
        ints(p.steps, out)
    }

    private fun phrases(input: DataInputStream): Phrases {
        val barLines = doubles(input)
        val barBeats = ints(input)
        val novelty = doubles(input)
        val barDb = doubles(input)
        val lastStrong = input.readInt()
        val candidates = List(count(input)) {
            val line = input.readInt()
            val step = input.readInt()
            val score = input.readDouble()
            PhraseCandidate(line, step, score)
        }
        val steps = ints(input)
        return Phrases(barLines, barBeats, novelty, barDb, lastStrong, candidates, steps)
    }

    private inline fun <T> maybe(value: T?, out: DataOutputStream, write: (T) -> Unit) {
        out.writeBoolean(value != null)
        if (value != null) write(value)
    }

    private inline fun <T> maybe(input: DataInputStream, read: () -> T): T? = if (input.readBoolean()) read() else null

    /** A length read back, refused when it cannot be one, so a damaged file fails instead of asking for gigabytes. */
    private fun count(input: DataInputStream): Int {
        val n = input.readInt()
        if (n < 0 || n > MAX_COUNT) throw IOException("a count of $n")
        return n
    }

    // Arrays in one block each: the guide alone is close to a million floats.
    private fun floats(a: FloatArray, out: DataOutputStream) {
        out.writeInt(a.size)
        val bytes = ByteBuffer.allocate(a.size * 4).order(ByteOrder.BIG_ENDIAN)
        bytes.asFloatBuffer().put(a)
        out.write(bytes.array())
    }

    private fun floats(input: DataInputStream): FloatArray {
        val bytes = ByteArray(count(input) * 4).also { input.readFully(it) }
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(it) }
    }

    private fun doubles(a: DoubleArray, out: DataOutputStream) {
        out.writeInt(a.size)
        val bytes = ByteBuffer.allocate(a.size * 8).order(ByteOrder.BIG_ENDIAN)
        bytes.asDoubleBuffer().put(a)
        out.write(bytes.array())
    }

    private fun doubles(input: DataInputStream): DoubleArray {
        val bytes = ByteArray(count(input) * 8).also { input.readFully(it) }
        return DoubleArray(bytes.size / 8).also { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asDoubleBuffer().get(it) }
    }

    private fun ints(a: IntArray, out: DataOutputStream) {
        out.writeInt(a.size)
        val bytes = ByteBuffer.allocate(a.size * 4).order(ByteOrder.BIG_ENDIAN)
        bytes.asIntBuffer().put(a)
        out.write(bytes.array())
    }

    private fun ints(input: DataInputStream): IntArray {
        val bytes = ByteArray(count(input) * 4).also { input.readFully(it) }
        return IntArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(it) }
    }

    /** More values than any region holds: a guide is a decimated copy of a few minutes at most. */
    private const val MAX_COUNT = 16_000_000
}
