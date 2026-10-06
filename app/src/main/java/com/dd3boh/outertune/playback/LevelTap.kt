/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Measures the music on its way to the output, for the player's living background, and answers
 * "how loud is what I am hearing right now".
 *
 * Those are two different moments. Samples are handed to the output a quarter of a second or more
 * before they are heard, and with Bluetooth headphones later still. So every level is kept under
 * the time its samples carry, and the question is answered with the time the output says it has
 * reached, which the player asks it every few milliseconds while it plays. A picture driven by the
 * samples as they arrive would move ahead of the music by a different amount on every device.
 *
 * This is not android.media.audiofx.Visualizer, which would do the same from outside: that one
 * needs the microphone permission, and a music player asking for the microphone is a bad look.
 *
 * It costs nothing unless [wanted]: the samples are not looked at. It hears nothing when the audio
 * is handed to the system still encoded (audio offload), and then [now] simply has no answer.
 */
class LevelTap(private val nanoTime: () -> Long = System::nanoTime) {

    private val watchers = AtomicInteger()

    /** True while something is drawing the levels. Nothing is measured otherwise. */
    val wanted get() = watchers.get() > 0

    /** Whoever draws the levels says so, and says when it stops: one [unwatch] for every [watch]. */
    fun watch() {
        watchers.incrementAndGet()
    }

    fun unwatch() {
        watchers.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    private val timeline = LevelTimeline()

    // What follows is touched by the thread that plays only.
    private var analyser: LevelAnalyser? = null
    private var sixteenBit = true
    private var channels = 0
    private var sampleRate = 0
    private var bufferTimeUs = 0L
    private var bufferFirstSample = 0L
    private var samplesGiven = 0L
    private var lastBufferTimeUs = C.TIME_UNSET
    private var nextBufferTimeUs = C.TIME_UNSET
    private var measuring = false

    // Written by the thread that plays, read by the one that draws.
    @Volatile
    private var heardUs = C.TIME_UNSET

    @Volatile
    private var heardAtNanos = 0L

    @Volatile
    private var playing = false

    /** The output was set up for a stream. [pcm] is false when the audio goes to it still encoded. */
    fun configured(pcm: Boolean, encoding: Int, sampleRate: Int, channels: Int) {
        val readable = pcm && sampleRate > 0 && channels > 0 &&
            (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)
        if (!readable) {
            analyser = null
            return
        }
        if (analyser != null && sampleRate == this.sampleRate && channels == this.channels && sixteenBit == (encoding == C.ENCODING_PCM_16BIT)) return
        sixteenBit = encoding == C.ENCODING_PCM_16BIT
        this.sampleRate = sampleRate
        this.channels = channels
        samplesGiven = 0
        val halfFrameUs = (MusicLevels.FRAME_SECONDS * 500_000).toLong()
        analyser = LevelAnalyser(sampleRate) { endsAtSample, levels ->
            // under the middle of the 20 ms it was measured over
            timeline.add(bufferTimeUs + (endsAtSample - bufferFirstSample) * 1_000_000L / sampleRate - halfFrameUs, levels)
        }
    }

    /**
     * Samples offered to the output, from [buffer]'s position to its limit, the first of them to
     * be heard at [timeUs]. The output may be offered the same buffer again when it could not take
     * all of it; it is measured once.
     */
    fun buffer(buffer: ByteBuffer, timeUs: Long) {
        val analyser = analyser
        if (!wanted || analyser == null) {
            measuring = false
            return
        }
        if (timeUs == lastBufferTimeUs) return
        lastBufferTimeUs = timeUs

        // Starting to listen, or the audio is not where the last buffer ended: a seek, a skipped silence.
        if (!measuring || nextBufferTimeUs == C.TIME_UNSET || abs(timeUs - nextBufferTimeUs) > JUMP_US) analyser.jump()
        measuring = true

        val frames = (buffer.limit() - buffer.position()) / ((if (sixteenBit) 2 else 4) * channels)
        bufferTimeUs = timeUs
        bufferFirstSample = samplesGiven
        samplesGiven += frames
        nextBufferTimeUs = timeUs + frames * 1_000_000L / sampleRate
        if (sixteenBit) analyser.pcm16(buffer, channels) else analyser.pcmFloat(buffer, channels)
    }

    /** Where the output says it has got to, in the same time as the buffers carry. */
    fun position(positionUs: Long) {
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) return
        heardAtNanos = nanoTime()
        heardUs = positionUs
    }

    fun playing(isPlaying: Boolean) {
        playing = isPlaying
    }

    /** The output was emptied: a seek, another song, a stop. Nothing kept is about to be heard any more. */
    fun flushed() {
        timeline.clear()
        lastBufferTimeUs = C.TIME_UNSET
        nextBufferTimeUs = C.TIME_UNSET
        heardUs = C.TIME_UNSET
    }

    /**
     * The levels of what is being heard now, into [into] ([MusicLevels.BANDS] values). False when
     * there is nothing to go by: nothing playing, not [wanted] until a moment ago, or audio that
     * cannot be read here.
     *
     * [aheadUs] asks for what will be heard that much later instead, which is known, since the
     * samples are measured before they are heard. Whoever draws them asks ahead by the time its
     * drawing takes to reach the screen. Asked for more than has been measured, it answers with
     * the latest there is.
     */
    fun now(into: FloatArray, aheadUs: Long = 0): Boolean {
        val at = heardUs
        if (at == C.TIME_UNSET || !playing) return false
        val since = (nanoTime() - heardAtNanos) / 1000
        // While it plays the output is asked where it is every few milliseconds. Not asked for this
        // long, it is not playing, whatever was said last.
        if (since < 0 || since > ASKED_LATELY_US) return false
        return timeline.read(at + since + aheadUs.coerceAtLeast(0), into)
    }

    companion object {
        private const val JUMP_US = 100_000L
        private const val ASKED_LATELY_US = 500_000L
    }
}

/** An audio output that lets a [LevelTap] look at what goes through it. It changes nothing. */
class LevelTapAudioSink(sink: AudioSink, private val tap: LevelTap) : ForwardingAudioSink(sink) {

    /** Set when the tap failed once. Playback is not to be risked for a background picture. */
    private var broken = false

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        tap.configured(
            pcm = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW,
            encoding = inputFormat.pcmEncoding,
            sampleRate = inputFormat.sampleRate,
            channels = inputFormat.channelCount,
        )
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!broken) {
            try {
                tap.buffer(buffer, presentationTimeUs)
            } catch (e: RuntimeException) {
                broken = true
                e.printStackTrace()
            }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also(tap::position)

    override fun play() {
        super.play()
        tap.playing(true)
    }

    override fun pause() {
        super.pause()
        tap.playing(false)
    }

    override fun flush() {
        super.flush()
        tap.flushed()
    }

    override fun reset() {
        super.reset()
        tap.flushed()
        tap.playing(false)
    }
}
