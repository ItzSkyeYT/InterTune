/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.util.Log
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
 *
 * And where the stage joins a song already playing ([joinIfWanted]).
 */
class AutomixAudioSink(sink: AudioSink, private val stage: AutomixAudioProcessor) : ForwardingAudioSink(sink) {

    private val entrySkip = EntrySkip(stage)

    /** The song playing as the renderer last configured it, so the stage can join it at a flush. */
    private var lastFormat: Format? = null
    private var lastBufferSize = 0
    private var lastOutputChannels: IntArray? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        lastFormat = inputFormat
        lastBufferSize = specifiedBufferSize
        lastOutputChannels = outputChannels
        entrySkip.configured(inputFormat)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
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
            joinIfWanted()
            super.flush()
        } finally {
            stage.rendererFlushing = false
        }
    }

    /**
     * The stage joins a stream only as it is configured, so AutoMix switched on in the middle of a
     * song waited for the next one, and the change out of the song playing stayed plain, while a
     * change has to play in the playlist it was made in (7 Nov 2026). Here the song playing is
     * configured again, as it is, just before a flush the renderer asked for applies it: the
     * sink's chain is flushed with the stage in it. AutomixController seeks to where the music is
     * for this as AutoMix comes on; a seek of the listener's does the same. Not EntrySkip's
     * configure: this is the same stream going on, not a new one.
     */
    private fun joinIfWanted() {
        val format = lastFormat ?: return
        if (!stage.wanted || stage.activeForStream) return
        try {
            super.configure(format, lastBufferSize, lastOutputChannels)
        } catch (e: AudioSink.ConfigurationException) {
            Log.w(TAG, "AutoMix: the stage could not join the song playing", e)
        }
    }

    override fun reset() {
        entrySkip.reset()
        lastFormat = null
        stage.rendererFlushing = true
        try {
            super.reset()
        } finally {
            stage.rendererFlushing = false
        }
        stage.sinkPlaying(false)
    }

    private companion object {
        const val TAG = "AutoMix"
    }
}
