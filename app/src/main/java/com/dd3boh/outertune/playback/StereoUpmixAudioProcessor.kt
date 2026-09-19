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

/**
 * Turns stereo into 5.1 so the platform spatialiser has something it will accept.
 *
 * Be clear about what this is. It is not spatial audio in the sense Apple Music means, where the
 * recording was mixed in Atmos and the surround information is real. YouTube Music serves stereo
 * and only stereo: across 5,022 stream formats this app has been handed, not one was multichannel.
 * Nothing can recover information that was never recorded, so this invents a surround field from
 * two channels. It is an effect.
 *
 * It exists because the alternative is nothing at all. This phone's spatialiser reports
 * mCapableSpatLevel 1, multichannel only, so it ignores stereo entirely and passes it straight
 * through. Handed six channels it will spatialise them, over the speaker or over Bluetooth
 * headphones. So the choice is between a synthesised field and no field, not between a synthesised
 * one and a real one.
 *
 * The matrix is the conventional one and deliberately conservative:
 *
 *  - front left and right carry the original channels untouched, so anything panned hard stays
 *    exactly where it was and the mix cannot collapse
 *  - centre takes the mono sum at -3 dB, which is where a vocal sits in almost every mix
 *  - the surrounds take the difference signal, the part of the recording that is already
 *    uncorrelated between the channels: reverb tails, room, wide pads. Out of phase with each
 *    other, which is what gives width
 *  - LFE is left silent. Synthesising bass from a stereo sum is where upmixers usually start
 *    sounding wrong, and the spatialiser does not need it
 *
 * [SURROUND] is well under unity on purpose. The failure mode of this kind of matrix is a hollow,
 * phasey middle when too much difference signal is thrown to the back, and quiet surrounds are a
 * far smaller mistake than loud ones.
 */
class StereoUpmixAudioProcessor : BaseAudioProcessor() {

    /**
     * Whether to upmix, changeable while the app runs.
     *
     * Volatile because the switch is flipped on the main thread and read on the audio thread. The
     * sink only asks [onConfigure] again when it reconfigures, so flipping this is not enough on
     * its own: MusicService restarts the current track to make it take hold. Without that the
     * setting appeared to do nothing, because a sink already configured for two channels goes on
     * producing two channels however the flag reads.
     */
    @Volatile
    var enabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        // Stereo only. Anything already multichannel is left alone, which is the right answer if
        // YouTube ever does start serving it, and mono is not worth the trouble.
        if (inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, OUT_CHANNELS, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * OUT_CHANNELS * (format.bytesPerFrame / 2))

        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames) {
                val l = inputBuffer.short.toFloat() / Short.MAX_VALUE
                val r = inputBuffer.short.toFloat() / Short.MAX_VALUE
                writeFrame(l, r) { v -> out.putShort(toPcm16(v)) }
            }

            C.ENCODING_PCM_FLOAT -> repeat(frames) {
                val l = inputBuffer.float
                val r = inputBuffer.float
                writeFrame(l, r) { v -> out.putFloat(v) }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** Android's 5.1 order: front left, front right, centre, LFE, back left, back right. */
    private inline fun writeFrame(l: Float, r: Float, put: (Float) -> Unit) {
        val centre = (l + r) * CENTRE
        val side = (l - r) * SURROUND
        put(l)
        put(r)
        put(centre)
        put(0f)
        put(side)
        put(-side)
    }

    private fun toPcm16(v: Float): Short {
        val scaled = v * Short.MAX_VALUE
        return when {
            scaled >= Short.MAX_VALUE.toFloat() -> Short.MAX_VALUE
            scaled <= Short.MIN_VALUE.toFloat() -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    companion object {
        const val OUT_CHANNELS = 6

        /** Mono sum into the centre, at -3 dB so summing does not raise the level. */
        const val CENTRE = 0.7071f

        /** How much difference signal reaches the back. Low on purpose; see the class comment. */
        const val SURROUND = 0.5f
    }
}
