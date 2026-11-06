/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Between DefaultAudioSink and the rest: tells AutoMix's stage what only the sink sees.
 *
 * Which flushes the renderer asked for. The sink flushes its chain for three reasons: a seek, a
 * stop or a song chosen by hand, which come from the renderer through here; the change of song it
 * drains for; and new playback parameters. The stage cannot tell the first from the others by
 * itself, so a flush from here is marked for the length of the call. The sink's own flushes, made
 * from inside it, never pass this way.
 *
 * Also where the output has got to, as the renderer asks for it, which paces the stage while it
 * holds an end; whether the sink plays; and the time of the buffer being handed over. It changes
 * nothing that passes through, but for a next song that a change brings in at a bar of its middle:
 * its frames before that bar are dropped here, before the sink, as they are decoded ([EntrySkip]).
 */
class AutomixAudioSink(sink: AudioSink, private val stage: AutomixAudioProcessor) : ForwardingAudioSink(sink) {

    private val entrySkip = EntrySkip(stage)

    // Debug builds: the buffers around a new stream, for the check that a later entry is counted from the stream's first frame.
    private var lastTimeUs = 0L
    private var lastBytes = 0
    private var afterConfigure = 0
    private var frameBytes = 4

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        entrySkip.configured(inputFormat)
        if (com.dd3boh.outertune.BuildConfig.DEBUG) {
            frameBytes = runCatching { androidx.media3.common.util.Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount) }.getOrDefault(4).coerceAtLeast(1)
            android.util.Log.i("AutoMix", "sink: configured (${inputFormat.sampleRate} Hz, delay ${inputFormat.encoderDelay}); the last buffer before at $lastTimeUs us, ${lastBytes / frameBytes} frames")
            afterConfigure = 3
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (com.dd3boh.outertune.BuildConfig.DEBUG) {
            if (afterConfigure > 0 && (presentationTimeUs != lastTimeUs || buffer.remaining() != lastBytes)) {
                afterConfigure--
                android.util.Log.i("AutoMix", "sink: buffer at $presentationTimeUs us, ${buffer.remaining() / frameBytes} frames, after the configure")
            }
            lastTimeUs = presentationTimeUs
            lastBytes = buffer.remaining()
        }
        stage.sinkInput(presentationTimeUs)
        return entrySkip.handle(buffer, presentationTimeUs) { b, t -> super.handleBuffer(b, t, if (b.hasRemaining()) encodedAccessUnitCount else 0) }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also {
            if (it != AudioSink.CURRENT_POSITION_NOT_SET) stage.sinkPosition(it)
        }

    override fun play() {
        super.play()
        stage.sinkPlaying(true)
    }

    override fun pause() {
        super.pause()
        stage.sinkPlaying(false)
    }

    override fun flush() {
        entrySkip.reset()
        stage.rendererFlushing = true
        try {
            super.flush()
        } finally {
            stage.rendererFlushing = false
        }
    }

    override fun reset() {
        entrySkip.reset()
        stage.rendererFlushing = true
        try {
            super.reset()
        } finally {
            stage.rendererFlushing = false
        }
        stage.sinkPlaying(false)
    }
}
