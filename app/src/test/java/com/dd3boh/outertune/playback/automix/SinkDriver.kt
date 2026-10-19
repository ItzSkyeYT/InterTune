/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.dd3boh.outertune.playback.GainAudioProcessor
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * media3 1.8.0's DefaultAudioSink and the renderer in front of it, as they drive the audio chain,
 * with AutoMix's stage last in a real AudioProcessingPipeline: for testing the stage the way the
 * phone runs it.
 *
 * Read from the 1.8.0 bytecode (the disassembly in experiments/features/automix/work/javap):
 * handleBuffer, which at a pending configuration first drains the chain to its end, then puts a
 * position checkpoint at the new stream's first buffer and flushes every processor; and which,
 * when the renderer has reported a new stream (handleDiscontinuity, sent once the last buffer of
 * the song before has been taken), drains the chain a second time before that first buffer goes
 * in, moves its start time and flushes again. processBuffers, which writes the pipeline's output to
 * the AudioTrack until it is full and queues input only once all output is written. The AudioTrack
 * is a queue played out in real time; the position is the latest checkpoint's media time plus what
 * the track has played since it.
 *
 * Time moves by [step]: the renderer hands over what it can, the track plays [tickMs].
 */
class SinkDriver(
    val stage: AutomixAudioProcessor,
    private val songs: List<ShortArray>,
    private val random: Random,
    val rate: Int = 44_100,
    val channels: Int = 2,
    private val trackFrames: Int = rate / 4,
    private val tickMs: Int = 10,
    /** The renderer's pace: up to this many decoded buffers a tick, about ten times real time. */
    private val buffersPerTick: Int = 4,
    /** No buffer for this long between the last of one song and the first of the next. */
    private val switchGapMs: Int = 30,
    /** Whether the renderer reports each new stream, so the sink drains once more as it starts. */
    private val reportsNewStream: Boolean = true,
) {
    private val format = AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)
    private val frameBytes = 2 * channels
    private val gain = GainAudioProcessor()
    val pipeline = AudioProcessingPipeline(ImmutableList.of(gain, stage))

    /** Everything written to the AudioTrack, in order: what is heard. */
    val heard = ShortList()
    var played = 0L
        private set
    var written = 0L
        private set
    var clockMs = 0L
        private set
    var underruns = 0
        private set

    /** Calls into the sink that moved nothing at all while input waited: the real sink would spin there. */
    var stalls = 0
        private set

    /** From [trackFrame] of the track on, [song] at its frame [songFrame]. */
    class Checkpoint(val trackFrame: Long, val song: Int, val songFrame: Long)

    val checkpoints = arrayListOf(Checkpoint(0, 0, 0))

    // The renderer: the song it reads, how far, and the buffer it is trying to hand over.
    var song = 0
        private set
    private var songFrame = 0
    private var buffer: Buffer? = null
    private var gapUntilMs = -1L
    var ended = false
        private set

    /** While true the renderer hands over nothing, as when the decoder is slow or starved. */
    var renderPaused = false

    /** False while the music is paused: the track plays nothing and the sink says so to the stage. */
    var trackPlaying = true
        set(value) {
            field = value
            stage.sinkPlaying(value)
        }

    // The sink.
    private var pendingConfiguration: Int? = null
    private var needsSync = false
    private var needsInit = true
    private var startSong = 0
    private var startFrame = 0L
    private var submittedFrames = 0L
    private var inputBuffer: Buffer? = null
    private var outputBuffer: ByteBuffer? = null

    class Buffer(val song: Int, val firstFrame: Int, val bytes: ByteBuffer)

    init {
        pipeline.configure(format)
        pipeline.flush()
        stage.sinkPlaying(true)
    }

    /** The song and its frame the output has reached, by the checkpoints, as getCurrentPositionUs works it out. */
    fun position(): Pair<Int, Long> {
        val cp = checkpoints.last { it.trackFrame <= played }
        return cp.song to cp.songFrame + (played - cp.trackFrame)
    }

    /** The position as one clock in microseconds, songs one after the other, as the sink's own time runs. */
    fun positionUs(): Long {
        val (s, f) = position()
        var frames = f
        for (k in 0 until s) frames += songs[k].size / channels
        return frames * 1_000_000L / rate
    }

    fun step() {
        stage.sinkPosition(positionUs())
        if (!renderPaused) render()
        if (trackPlaying) {
            val target = played + rate.toLong() * tickMs / 1000
            val before = played
            played = min(written, target)
            if (played < target && !ended && before == played) underruns++
        }
        clockMs += tickMs
    }

    /** A seek, or a song chosen by hand: the renderer flushes the sink and starts again at [toFrame] of [toSong]. */
    fun seek(toSong: Int, toFrame: Int) {
        stage.rendererFlushing = true
        try {
            pipeline.flush()
        } finally {
            stage.rendererFlushing = false
        }
        outputBuffer = null
        inputBuffer = null
        buffer = null
        pendingConfiguration = if (toSong != song) toSong else null
        if (toSong != song) pipeline.configure(format)
        song = toSong
        songFrame = toFrame
        needsSync = false
        needsInit = true
        played = written
        checkpoints += Checkpoint(written, toSong, toFrame.toLong())
        startSong = toSong
        startFrame = toFrame.toLong()
        submittedFrames = 0
    }

    private fun render() {
        var handed = 0
        while (handed < buffersPerTick && !ended) {
            val b = buffer ?: nextBuffer() ?: return
            buffer = b
            val before = written
            val inputBefore = b.bytes.remaining()
            val taken = handleBuffer(b)
            if (!taken && written == before && b.bytes.remaining() == inputBefore && outputBuffer == null && !pipeline.isEnded()) stalls++
            if (!taken) return
            buffer = null
            handed++
            // The renderer says a new stream began once the last buffer of the old one is taken.
            if (reportsNewStream && b.firstFrame + b.bytes.limit() / frameBytes >= songs[b.song].size / channels && b.song + 1 < songs.size) needsSync = true
        }
    }

    private fun nextBuffer(): Buffer? {
        val samples = songs[song]
        val frames = samples.size / channels
        if (songFrame >= frames) {
            if (song + 1 >= songs.size) {
                // The end of the queue: the renderer plays the sink to its end.
                if (drainToEndOfStream()) ended = true
                return null
            }
            if (gapUntilMs < 0) gapUntilMs = clockMs + switchGapMs
            if (clockMs < gapUntilMs) return null
            gapUntilMs = -1
            song++
            songFrame = 0
            // The first buffer of the next song brings its format: the sink is configured, and
            // the processors with it, while the old stream is still in the chain.
            pipeline.configure(format)
            pendingConfiguration = song
        }
        val next = songs[song]
        val n = min(next.size / channels - songFrame, 384 + random.nextInt(2048))
        val bytes = ByteBuffer.allocateDirect(n * frameBytes).order(ByteOrder.nativeOrder())
        bytes.asShortBuffer().put(next, songFrame * channels, n * channels)
        val b = Buffer(song, songFrame, bytes)
        songFrame += n
        return b
    }

    private fun timeUsOf(song: Int, frame: Long): Long {
        var frames = frame
        for (k in 0 until song) frames += songs[k].size / channels
        return frames * 1_000_000L / rate
    }

    private fun handleBuffer(b: Buffer): Boolean {
        stage.sinkInput(timeUsOf(b.song, b.firstFrame.toLong()))
        if (pendingConfiguration != null) {
            if (!drainToEndOfStream()) return false
            pendingConfiguration = null
            // The track is reused: the new stream counts from here, and every processor is flushed.
            applyParameters(b)
        }
        if (needsInit) {
            startSong = b.song
            startFrame = b.firstFrame.toLong()
            submittedFrames = 0
            needsSync = false
            needsInit = false
            applyParameters(b)
        }
        if (inputBuffer == null) {
            if (!b.bytes.hasRemaining()) return true
            val expected = timeUsOf(startSong, startFrame) + submittedFrames * 1_000_000L / rate
            val actual = timeUsOf(b.song, b.firstFrame.toLong())
            if (!needsSync && abs(expected - actual) > 200_000) needsSync = true
            if (needsSync) {
                if (!drainToEndOfStream()) return false
                needsSync = false
                applyParameters(b)
            }
            submittedFrames += b.bytes.remaining() / frameBytes
            inputBuffer = b
        }
        processBuffers()
        if (!inputBuffer!!.bytes.hasRemaining()) {
            inputBuffer = null
            return true
        }
        return false
    }

    /** The position from here is the stream's own, at the frames written so far; the chain is flushed. */
    private fun applyParameters(b: Buffer) {
        val pending = outputBuffer?.remaining()?.div(frameBytes) ?: 0
        val cp = Checkpoint(written + pending, b.song, b.firstFrame.toLong())
        if (checkpoints.last().trackFrame == cp.trackFrame) checkpoints[checkpoints.size - 1] = cp else checkpoints += cp
        pipeline.flush()
    }

    private fun drainToEndOfStream(): Boolean {
        pipeline.queueEndOfStream()
        processBuffers()
        return pipeline.isEnded() && (outputBuffer == null || !outputBuffer!!.hasRemaining())
    }

    private fun processBuffers() {
        drainOutputBuffer()
        if (outputBuffer != null) return
        var idle = 0
        while (!pipeline.isEnded()) {
            var wrote = false
            var out = pipeline.getOutput()
            while (out.hasRemaining()) {
                wrote = true
                outputBuffer = out
                drainOutputBuffer()
                if (outputBuffer != null) return
                out = pipeline.getOutput()
            }
            val input = inputBuffer ?: return
            if (!input.bytes.hasRemaining()) return
            val before = input.bytes.remaining()
            pipeline.queueInput(input.bytes)
            // Nothing written and nothing taken, with input waiting: the real loop would go round
            // again and again on the player's thread.
            if (!wrote && input.bytes.remaining() == before) {
                if (++idle > 3) {
                    stalls++
                    return
                }
            } else {
                idle = 0
            }
        }
    }

    private fun drainOutputBuffer() {
        val out = outputBuffer ?: return
        val room = (trackFrames - (written - played)).toInt()
        val n = min(room, out.remaining() / frameBytes)
        if (n > 0) {
            val chunk = ShortArray(n * channels)
            out.asShortBuffer().get(chunk)
            out.position(out.position() + n * frameBytes)
            heard.add(chunk, 0, chunk.size)
            written += n
        }
        if (!out.hasRemaining()) outputBuffer = null
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

    operator fun get(i: Int): Short = data[i]

    fun toArray(): ShortArray = data.copyOf(size)
}
