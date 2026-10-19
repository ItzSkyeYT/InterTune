/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

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
 * nothing that passes through.
 */
class AutomixAudioSink(sink: AudioSink, private val stage: AutomixAudioProcessor) : ForwardingAudioSink(sink) {

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        stage.sinkInput(presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
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
        stage.rendererFlushing = true
        try {
            super.flush()
        } finally {
            stage.rendererFlushing = false
        }
    }

    override fun reset() {
        stage.rendererFlushing = true
        try {
            super.reset()
        } finally {
            stage.rendererFlushing = false
        }
        stage.sinkPlaying(false)
    }
}
