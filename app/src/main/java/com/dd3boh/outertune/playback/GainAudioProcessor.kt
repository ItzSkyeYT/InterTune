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
import kotlin.math.roundToInt

/**
 * Applies gain to the PCM stream, including gain above unity.
 *
 * This exists because [androidx.media3.exoplayer.ExoPlayer.setVolume] hard-clamps to [0, 1]
 * (ExoPlayerImpl calls `Util.constrainValue(v, 0f, 1f)`), so the player's own volume can only ever
 * attenuate. Making audio *louder* has to happen on the samples, which is what this is for: volume
 * boost past 100%, the way VLC and friends offer it.
 *
 * Attenuation deliberately does NOT happen here. Turning things down is left to the sink, so this
 * processor is a straight passthrough for any gain at or below unity. See [gain].
 *
 * Boosting can clip, and loudness metadata says nothing about peaks, so everything past
 * [SOFT_CLIP_THRESHOLD] is compressed into the remaining headroom rather than being allowed to wrap
 * or slam into the rail. The curve is continuous in both value and slope at the threshold, so there
 * is no audible kink when a signal crosses it, and it approaches full scale asymptotically without
 * ever exceeding it.
 *
 * OFFLOAD: audio offload and passthrough bypass the processor chain entirely, because
 * DefaultAudioSink.configure builds an empty AudioProcessingPipeline for any sampleMimeType other
 * than audio/raw, so this class never runs. Loudness normalisation no longer depends on it: gain at
 * or below unity is applied through AudioTrack.setVolume, which is not offload gated. The only
 * thing genuinely lost under offload is gain ABOVE unity, and that is physically unavailable there,
 * since the chain is out of the pipeline and ExoPlayerImpl.setVolume clamps to [0, 1]. media3 has
 * no third gain stage to fall back on.
 */
class GainAudioProcessor : BaseAudioProcessor() {

    /**
     * Total linear gain asked for: loudness normalisation multiplied by user volume.
     *
     * Only the part ABOVE unity is applied here. Everything at or below unity is handed to
     * AudioTrack.setVolume by MusicService instead, because that path keeps working when audio
     * offload bypasses this processor, and because it avoids requantising 16-bit samples in the
     * app for the overwhelmingly common case of turning a loud track down.
     */
    // Volatile: written from the player thread, read on the audio thread. A 32-bit float cannot
    // tear, and a one-buffer-late value is inaudible, so nothing stronger is needed. Keeping this
    // as ONE field rather than a normalise/volume pair also means a buffer can never observe a
    // half-updated combination and apply a gain nobody asked for.
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
        val total = gain // one volatile read for the whole buffer

        if (total <= 1f) {
            // Attenuation is the sink's job, so there is nothing to do here but hand the samples
            // straight through, bit for bit. Note this is not merely an optimisation: running the
            // old maths at, say, 0.9 would soft clip every sample above 0.83 of full scale even
            // though the output physically cannot clip, which is audible non-linearity added for
            // no reason, on top of a pointless 16-bit round trip.
            output.put(inputBuffer)
        } else when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = position
                while (i < limit - 1) {
                    val sample = inputBuffer.getShort(i).toFloat() / SHORT_SCALE
                    val scaled = softClip(sample * total) * SHORT_SCALE
                    // roundToInt, not toInt. Truncation biases every sample toward zero, and an
                    // error that tracks the signal is distortion rather than noise. coerceIn is
                    // belt and braces, since softClip is already strictly inside (-1, 1).
                    output.putShort(scaled.roundToInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort())
                    i += 2
                }
                inputBuffer.position(limit)
            }

            C.ENCODING_PCM_FLOAT -> {
                var i = position
                while (i < limit - 3) {
                    output.putFloat(softClip(inputBuffer.getFloat(i) * total))
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

        /** Signed 16-bit rails, for the coerceIn guard on the boost path. */
        const val SHORT_MIN = -32768
        const val SHORT_MAX = 32767
    }
}
