/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A song as the renderer decodes it. */
class ModelSong(val name: String, val samples: ShortArray, val channels: Int = 2) {
    val frames: Int get() = samples.size / channels
}

/**
 * The audio path around the mixer, as media3 1.8.0's DefaultAudioSink runs it, for driving
 * [HeldTail] the way the phone will.
 *
 * Modelled from the sink's source and bytecode: handleBuffer, which at a pending configuration
 * first drains the chain to its end, then checkpoints the position at the new stream's first
 * frame and flushes every processor; processBuffers, which writes the pipeline's output to the
 * AudioTrack until the track is full and queues input only once the output is all written; the
 * pipeline's processData loop, which keeps calling its last stage with empty input while that
 * stage has not ended. The mixer is the only processor. The AudioTrack is a queue of [trackFrames]
 * played out in real time, and the position is the checkpoint's media time plus what the track has
 * played since the checkpoint, as getCurrentPositionUs works it out.
 *
 * Everything is in frames and model time; [step] advances the clock by [tickMs], as the player's
 * work loop does.
 */
class SinkModel(
    val held: HeldTail,
    val songs: List<ModelSong>,
    private val random: Random,
    val rate: Int = 44_100,
    val channels: Int = 2,
    val trackFrames: Int = rate / 4,
    /** While capturing, how far ahead of what the track has played the mixer keeps it fed. */
    val captureLead: Int = (0.2 * rate).toInt(),
    /** Time with no buffer between the last of one song and the first of the next. */
    val switchGapMs: Int = 30,
    val tickMs: Int = 10,
    /** The decoder's pace, in buffers a tick at most: four of about 1,400 frames, ten times real time. */
    val buffersPerTick: Int = 4,
    /** Whether the next song comes in this one's format; when not, the mixer is told so at the drain. */
    val formatsMatch: Boolean = true,
) {
    /** Every frame written to the AudioTrack, in order: what is heard. */
    val heard = ShortList()
    var played = 0L
        private set
    var written = 0L
        private set
    var underruns = 0
        private set
    var clockMs = 0L
        private set

    /** Where the track's position counts from: from this track frame on, song [song] at frame [songFrame]. */
    class Checkpoint(val trackFrame: Long, val song: Int, val songFrame: Long)

    val checkpoints = arrayListOf(Checkpoint(0, 0, 0))

    /** How often a pass through the sink's loop moved nothing at all; the real sink would spin. */
    var stalls = 0
        private set

    // The renderer: which song, how far into it, and the buffer it is trying to hand over.
    var song = 0
        private set
    var songPosition = 0
        private set
    private var buffer: Buffer? = null
    private var gapUntilMs = -1L
    var ended = false
        private set

    // The sink.
    private var pendingConfiguration: Int? = null
    private var inputBuffer: Buffer? = null

    // The pipeline of one processor: its output buffer, and whether its input has ended.
    private val processorOut = ShortArray(8192 * channels)
    private var outFrom = 0
    private var outTo = 0
    private var pipelineInputEnded = false
    private var configureSeen = false

    class Buffer(val song: Int, val samples: ShortArray, val from: Int, val frames: Int) {
        var position = 0
        val remaining: Int get() = frames - position
    }

    /** How far into the current song the mixer has been handed frames: the decoder's position less what it still holds. */
    fun consumedPosition(): Int = songPosition - (buffer?.remaining ?: 0)

    /** The frames of the song playing now, by the sink's own reckoning, or null before anything plays. */
    fun position(): kotlin.Pair<Int, Long> {
        val cp = checkpoints.last { it.trackFrame <= played }
        return cp.song to cp.songFrame + (played - cp.trackFrame)
    }

    /** One tick: the renderer hands over what it can, then the track plays for [tickMs]. */
    fun step() {
        render()
        val frames = rate.toLong() * tickMs / 1000
        val target = played + frames
        played = min(written, target)
        if (played < target && !ended) underruns++
        clockMs += tickMs
    }

    /** A flush from the renderer, as a seek or a new song chosen by hand gives: everything is dropped. */
    fun seek(toSong: Int, toFrame: Int) {
        held.flush(HeldTail.Flush.RENDERER)
        pipelineInputEnded = false
        outFrom = 0
        outTo = 0
        pendingConfiguration = null
        configureSeen = false
        inputBuffer = null
        buffer = null
        song = toSong
        songPosition = toFrame
        // A flushed track starts over; the position counts from the seek.
        played = written
        checkpoints += Checkpoint(written, toSong, toFrame.toLong())
    }

    private fun render() {
        var handed = 0
        while (handed < buffersPerTick && !ended) {
            val b = buffer ?: nextBuffer() ?: return
            buffer = b
            if (!handleBuffer(b)) return
            buffer = null
            handed++
        }
    }

    private fun nextBuffer(): Buffer? {
        if (song >= songs.size) return null
        val s = songs[song]
        if (songPosition >= s.frames) {
            // The end of this song: the decoder switches to the next, or the queue is over.
            if (song + 1 >= songs.size) {
                if (playToEndOfStream()) ended = true
                return null
            }
            if (gapUntilMs < 0) gapUntilMs = clockMs + switchGapMs
            if (clockMs < gapUntilMs) return null
            gapUntilMs = -1
            song++
            songPosition = 0
            // The next song's first decoded buffer brings its format: the sink is configured,
            // and the processors with it, while the old stream is still in the chain.
            pendingConfiguration = song
            configureSeen = true
        }
        val s2 = songs[song]
        val n = min(s2.frames - songPosition, 384 + random.nextInt(2048))
        val b = Buffer(song, s2.samples, songPosition, n)
        songPosition += n
        return b
    }

    private fun handleBuffer(b: Buffer): Boolean {
        if (pendingConfiguration != null) {
            if (!drainToEndOfStream()) return false
            // The track is reused: the new stream's position counts from here, and every
            // processor is flushed for it.
            checkpoints += Checkpoint(written + pendingFrames(), pendingConfiguration!!, 0)
            pendingConfiguration = null
            flushPipeline(if (configureSeen) HeldTail.Flush.SONG_CHANGE else HeldTail.Flush.PARAMETERS)
        }
        if (inputBuffer == null) inputBuffer = b
        processBuffers()
        if (inputBuffer!!.remaining == 0) {
            inputBuffer = null
            return true
        }
        return false
    }

    private fun pendingFrames(): Int = outTo - outFrom

    private fun playToEndOfStream(): Boolean = drainToEndOfStream()

    private fun drainToEndOfStream(): Boolean {
        if (!pipelineInputEnded) {
            pipelineInputEnded = true
            held.endOfStream(songChangePending = configureSeen, formatMatches = formatsMatch)
        }
        processBuffers()
        return pipelineEnded() && outTo == outFrom
    }

    private fun processBuffers() {
        drainOutput()
        if (outTo > outFrom) return
        var idle = 0
        while (!pipelineEnded()) {
            while (outputFromPipeline()) {
                drainOutput()
                if (outTo > outFrom) return
            }
            val input = inputBuffer
            if (input == null || input.remaining == 0) return
            val before = input.remaining
            processData(input)
            if (input.remaining == before && outTo == outFrom) {
                // Input waiting, and nothing taken and nothing given: the real loop would spin.
                stalls++
                if (++idle > 3) return
            }
        }
    }

    /** The pipeline's getOutput: what its last stage holds, or another pass of processData. */
    private fun outputFromPipeline(): Boolean {
        if (outTo > outFrom) return true
        processData(null)
        return outTo > outFrom
    }

    private fun processData(input: Buffer?) {
        var progress = true
        while (progress) {
            progress = false
            if (outTo > outFrom) return
            if (pipelineInputEnded && held.isEnded()) return
            val frames = if (pipelineInputEnded) 0 else input?.remaining ?: 0
            val maxOut = allowance(frames)
            val produced = held.process(
                input?.samples ?: EMPTY, (input?.let { it.from + it.position } ?: 0), frames,
                processorOut, 0, maxOut,
            )
            val consumed = held.lastConsumed
            if (input != null) input.position += consumed
            outFrom = 0
            outTo = produced
            progress = consumed > 0 || produced > 0
        }
    }

    /**
     * How much the mixer may give out on this call: everything it is given while it passes or
     * mixes; while it captures, enough to keep the track [captureLead] ahead of what has played,
     * so input keeps being taken faster than real time; while it drains, as much as the track
     * takes, the sink's own back pressure.
     */
    private fun allowance(inputFrames: Int): Int {
        val cap = processorOut.size / channels
        return when (held.state) {
            HeldTail.State.PASS, HeldTail.State.MIX -> min(inputFrames, cap)
            HeldTail.State.CAPTURE -> {
                val ahead = written - played
                val room = (captureLead - ahead).toInt()
                // With input it cannot take (its memory full), it must give, or the sink would spin.
                if (room <= 0 && inputFrames > 0 && !held.canTakeInput()) min(1024, cap) else max(0, min(room, cap))
            }
            HeldTail.State.DRAIN -> min(1024, cap)
        }
    }

    private fun drainOutput() {
        if (outTo <= outFrom) return
        val room = (trackFrames - (written - played)).toInt()
        val n = min(room, outTo - outFrom)
        if (n <= 0) return
        heard.add(processorOut, outFrom * channels, n * channels)
        outFrom += n
        written += n
    }

    private fun pipelineEnded(): Boolean = pipelineInputEnded && held.isEnded() && outTo == outFrom

    private fun flushPipeline(kind: HeldTail.Flush) {
        pipelineInputEnded = false
        outFrom = 0
        outTo = 0
        configureSeen = false
        held.flush(kind)
    }

    private companion object {
        val EMPTY = ShortArray(0)
    }
}

/** A ShortArray that grows. */
class ShortList(capacity: Int = 1 shl 16) {
    private var data = ShortArray(capacity)
    var size = 0
        private set

    fun add(source: ShortArray, from: Int, count: Int) {
        while (size + count > data.size) data = data.copyOf(data.size * 2)
        System.arraycopy(source, from, data, size, count)
        size += count
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
