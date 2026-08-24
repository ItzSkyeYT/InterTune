/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Applies gain to the PCM stream, including gain above unity.
 *
 * This exists because [androidx.media3.exoplayer.ExoPlayer.setVolume] hard-clamps to [0, 1]
 * (ExoPlayerImpl calls `Util.constrainValue(v, 0f, 1f)`), so the player's own volume can only ever
 * attenuate. Anything that needs to make audio *louder* has to do it on the samples:
 *
 *  - volume boost past 100%, the way VLC and friends offer it
 *  - loudness normalisation that raises quiet tracks instead of only lowering loud ones, which is
 *    the difference between "consistent" and "consistently quiet"
 *
 * Boosting can clip, and loudness metadata says nothing about peaks, so everything past
 * [SOFT_CLIP_THRESHOLD] is compressed into the remaining headroom rather than being allowed to wrap
 * or slam into the rail. The curve is continuous in both value and slope at the threshold, so there
 * is no audible kink when a signal crosses it, and it approaches full scale asymptotically without
 * ever exceeding it.
 *
 * CAVEAT: audio offload bypasses the processor chain entirely, because offload hands compressed
 * data straight to the DSP. With offload enabled this processor never runs and gain silently has no
 * effect. Offload is off by default (`AudioOffloadKey`), but anything depending on gain should say
 * so where the user can see it.
 */
class GainAudioProcessor : BaseAudioProcessor() {

    /**
     * Linear gain. 1.0 is unity, 2.0 is +6 dB, 0.5 is -6 dB.
     *
     * Volatile because it is written from the player thread and read on the audio thread. A torn
     * read is not possible for a 32-bit float, and a one-buffer-late value is inaudible, so no
     * stronger synchronisation is needed.
     */
    @Volatile
    var gain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 16-bit and float are the two encodings that actually reach here. Anything else is
        // declared unhandled, which leaves this processor inactive and the stream untouched rather
        // than mangled.
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return

        val output = replaceOutputBuffer(size)
        val g = gain

        if (g == 1f) {
            // Unity: straight copy. The chain still runs, but this is a memcpy of a few kB per
            // buffer, which is not worth the reconfigure churn of toggling isActive() at runtime.
            output.put(inputBuffer)
        } else when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = position
                while (i < limit - 1) {
                    val sample = inputBuffer.getShort(i).toFloat() / SHORT_SCALE
                    output.putShort((softClip(sample * g) * SHORT_SCALE).toInt().toShort())
                    i += 2
                }
                inputBuffer.position(limit)
            }

            C.ENCODING_PCM_FLOAT -> {
                var i = position
                while (i < limit - 3) {
                    output.putFloat(softClip(inputBuffer.getFloat(i) * g))
                    i += 4
                }
                inputBuffer.position(limit)
            }

            else -> output.put(inputBuffer)
        }

        output.flip()
    }

    /**
     * Compresses anything past [SOFT_CLIP_THRESHOLD] into the headroom above it.
     *
     * Below the threshold the signal is untouched, so ordinary material passes through bit-exact
     * and only peaks that would otherwise clip are shaped. Above it the excess is mapped through
     * `over / (over + range)`, which is 0 at the threshold and tends to 1 as the input grows, so the
     * output tends to full scale but never reaches or exceeds it.
     *
     * The slope is 1 on both sides of the threshold, so the join is smooth: a hard knee here would
     * be audible as distortion the moment a signal crossed it.
     */
    private fun softClip(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= SOFT_CLIP_THRESHOLD) return x

        val over = magnitude - SOFT_CLIP_THRESHOLD
        val range = 1f - SOFT_CLIP_THRESHOLD
        val compressed = SOFT_CLIP_THRESHOLD + range * (over / (over + range))
        return if (x < 0f) -compressed else compressed
    }

    private companion object {
        /**
         * Where soft clipping begins, as a fraction of full scale. -2.5 dBFS.
         *
         * High enough that normal playback never touches it, low enough to leave real headroom to
         * shape peaks into.
         */
        const val SOFT_CLIP_THRESHOLD = 0.75f

        /** 16-bit samples are signed, so full scale is 32768 downward and 32767 upward. */
        const val SHORT_SCALE = 32767f
    }
}
