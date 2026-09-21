/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * A few seconds of whatever the microphone can hear, at the one rate the fingerprinter accepts.
 *
 * [SIGNATURE_SAMPLE_RATE_HZ] is 16 kHz and is not negotiable: the signature format encodes the rate
 * and Shazam rejects anything else. AudioRecord is asked for exactly that rather than for the
 * device's native rate followed by a resample, which is both simpler and avoids the question of
 * what a cheap resampler does to the 8 kHz band the fingerprint leans on. Every device since
 * API 21 supports 16 kHz mono capture.
 */
object MicrophoneSnippet {

    private const val TAG = "MicrophoneSnippet"

    /**
     * MIC rather than VOICE_RECOGNITION or VOICE_COMMUNICATION.
     *
     * Those two ask the platform for noise suppression, echo cancellation and automatic gain, all
     * of which are tuned to keep speech and discard everything else. Music is exactly the
     * everything else, and a fingerprint taken through them loses the quiet peaks it is built from.
     */
    private const val SOURCE = MediaRecorder.AudioSource.MIC

    /**
     * Records [seconds] of mono 16-bit audio.
     *
     * The caller is responsible for holding RECORD_AUDIO; without it AudioRecord initialises and
     * then returns silence rather than throwing, which would look like a room with nothing playing
     * in it.
     *
     * @return the samples, or null if the recorder would not start.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(seconds: Int = RECOGNITION_SECONDS): ShortArray? = withContext(Dispatchers.IO) {
        val wanted = SIGNATURE_SAMPLE_RATE_HZ * seconds

        val minBuffer = AudioRecord.getMinBufferSize(
            SIGNATURE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "No usable buffer size at ${SIGNATURE_SAMPLE_RATE_HZ}Hz: $minBuffer")
            return@withContext null
        }

        // Four times the minimum. The minimum is what the hardware needs to not underrun, not what
        // a reader doing work between reads needs, and a short buffer drops samples in the middle
        // of the clip where they are least recoverable.
        val record = AudioRecord(
            SOURCE,
            SIGNATURE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 4,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord did not initialise")
            record.release()
            return@withContext null
        }

        val out = ShortArray(wanted)
        var filled = 0
        try {
            record.startRecording()
            while (filled < wanted) {
                coroutineContext.ensureActive()
                val read = record.read(out, filled, wanted - filled)
                if (read <= 0) {
                    Log.w(TAG, "read returned $read after $filled samples")
                    break
                }
                filled += read
            }
        } finally {
            // stop() throws if it never started, and there is nothing useful to do about that
            // while already unwinding.
            runCatching { record.stop() }
            record.release()
        }

        // A clip cut short still fingerprints, just from less. Below a couple of seconds there is
        // not enough for the generator to find peaks in, so say so rather than ask Shazam about
        // silence.
        if (filled < SIGNATURE_SAMPLE_RATE_HZ * 2) {
            Log.w(TAG, "Only $filled samples, not enough to identify anything")
            return@withContext null
        }

        if (filled == wanted) out else out.copyOf(filled)
    }
}
