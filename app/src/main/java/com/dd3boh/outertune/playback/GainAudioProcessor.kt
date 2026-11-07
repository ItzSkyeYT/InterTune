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
import kotlin.math.exp
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
 * Boosting would clip, and loudness metadata says nothing about peaks, so a peak limiter keeps the
 * boosted music under [CEILING]: when a peak comes the gain comes down to meet it, holds for
 * [HOLD_SECONDS] (longer than a bass note's half cycle, so the gain does not wobble with each cycle),
 * then comes back over [RELEASE_SECONDS]. Between peaks the samples are only scaled, so the music
 * keeps its shape. It used to be an instant soft clip of every sample past -2.5 dBFS, and at 4x most
 * of a mastered song is past it: the sound distorted as soon as the volume passed 100% (#93, a
 * Galaxy M30s, 7 Nov 2026). No look-ahead, so nothing is delayed and the stage after this one keeps
 * its frames where they were.
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

    /** The limiter's level: the loudest boosted peak lately, held, then let go. Audio thread only. */
    private var envelope = 0f
    private var holdLeft = 0
    private var holdFrames = 0
    private var release = 1f

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

    override fun onFlush() {
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        holdFrames = (HOLD_SECONDS * rate).toInt()
        release = exp(-1.0 / (RELEASE_SECONDS * rate)).toFloat()
        envelope = 0f
        holdLeft = 0
    }

    override fun onReset() {
        envelope = 0f
        holdLeft = 0
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
            // straight through, bit for bit. Note this is not merely an optimisation: shaping
            // samples that cannot clip would be audible non-linearity added for no reason, on top
            // of a pointless 16-bit round trip. The next boost starts the limiter from rest.
            envelope = 0f
            holdLeft = 0
            output.put(inputBuffer)
        } else when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                val frameBytes = 2 * channels
                var i = position
                while (i + frameBytes <= limit) {
                    // The frame's loudest channel decides for all of them, so the stereo image holds.
                    var peak = 0f
                    for (c in 0 until channels) peak = maxOf(peak, abs(inputBuffer.getShort(i + 2 * c).toFloat() / SHORT_SCALE))
                    val g = total * limiterGain(peak * total)
                    for (c in 0 until channels) {
                        val scaled = inputBuffer.getShort(i + 2 * c).toFloat() * g
                        // roundToInt, not toInt. Truncation biases every sample toward zero, and an
                        // error that tracks the signal is distortion rather than noise.
                        output.putShort(scaled.roundToInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort())
                    }
                    i += frameBytes
                }
                inputBuffer.position(limit)
            }

            C.ENCODING_PCM_FLOAT -> {
                val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                val frameBytes = 4 * channels
                var i = position
                while (i + frameBytes <= limit) {
                    var peak = 0f
                    for (c in 0 until channels) peak = maxOf(peak, abs(inputBuffer.getFloat(i + 4 * c)))
                    val g = total * limiterGain(peak * total)
                    for (c in 0 until channels) output.putFloat((inputBuffer.getFloat(i + 4 * c) * g).coerceIn(-1f, 1f))
                    i += frameBytes
                }
                inputBuffer.position(limit)
            }

            else -> output.put(inputBuffer)
        }
        output.flip()
    }

    /**
     * The gain that keeps a frame whose boosted peak is [boosted] under [CEILING]: 1 while the
     * level stays under it. A new peak takes the level at once; it is held [holdFrames] frames,
     * then falls back by [release] a frame, never below what is playing.
     */
    private fun limiterGain(boosted: Float): Float {
        if (boosted >= envelope) {
            envelope = boosted
            holdLeft = holdFrames
        } else if (holdLeft > 0) {
            holdLeft--
        } else {
            envelope = maxOf(boosted, envelope * release)
        }
        return if (envelope > CEILING) CEILING / envelope else 1f
    }

    private companion object {
        /** The highest a boosted sample goes, as a fraction of full scale: -1 dBFS. */
        const val CEILING = 0.89f

        /** How long the limiter holds its level after a peak: past a 20 Hz half cycle. */
        const val HOLD_SECONDS = 0.05

        /** How fast the boost comes back once the hold is over: the time constant. */
        const val RELEASE_SECONDS = 0.2

        /** 16-bit samples are signed, so full scale is 32768 downward and 32767 upward. */
        const val SHORT_SCALE = 32767f

        /** Signed 16-bit rails, for the coerceIn guard on the boost path. */
        const val SHORT_MIN = -32768
        const val SHORT_MAX = 32767
    }
}
