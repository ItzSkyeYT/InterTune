/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Records what the microphone hears, in exactly the shape the fingerprinter demands.
 *
 * 16000 Hz mono signed 16-bit, asked of [AudioRecord] directly rather than recorded at the device
 * rate and resampled. Every Android device supports 16 kHz for capture, and a naive resampler is
 * one of the ways a fingerprint quietly stops matching: linear interpolation aliases everything
 * above 8 kHz down into the bands the signature is built from.
 *
 * [MediaRecorder.AudioSource.UNPROCESSED] where the device offers it, because the default source
 * applies whatever noise suppression and automatic gain the phone likes, and both alter the
 * spectrum the fingerprint is made of. Falls back when unavailable, since a processed recording
 * still matches most of the time and no recording never does.
 */
@Singleton
class MicrophoneListener @Inject constructor() {

    /**
     * Records up to [seconds], stopping early if the coroutine is cancelled.
     *
     * Returns whatever was captured before the stop rather than throwing it away, so a user who
     * taps stop after eight seconds still gets a recognition attempt out of it. Shazam matches
     * comfortably from about five.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    suspend fun record(
        seconds: Int = DEFAULT_SECONDS,
        onProgress: (elapsedMs: Long, level: Float) -> Unit = { _, _ -> },
    ): ShortArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SIGNATURE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "16 kHz mono capture is unavailable on this device" }

        val recorder = open(minBuffer)
            ?: throw IllegalStateException("Could not open the microphone")

        val wanted = SIGNATURE_SAMPLE_RATE_HZ * seconds
        val out = ShortArray(wanted)
        var written = 0

        try {
            recorder.startRecording()
            val chunk = ShortArray(CHUNK_SAMPLES)
            val started = System.currentTimeMillis()

            while (written < wanted && currentCoroutineContext().isActive) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, wanted - written))
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned $read, stopping")
                    break
                }
                chunk.copyInto(out, written, 0, read)
                written += read

                // Peak of the chunk, for the waveform the sheet draws while it listens. Cheap
                // enough at 1600 samples that it does not deserve its own thread.
                var peak = 0
                for (i in 0 until read) peak = maxOf(peak, abs(chunk[i].toInt()))
                onProgress(System.currentTimeMillis() - started, peak / 32768f)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        Log.i(TAG, "Recorded $written samples (${"%.1f".format(written / 16000f)}s)")
        if (written == wanted) out else out.copyOf(written)
    }

    @SuppressLint("MissingPermission")
    private fun open(minBuffer: Int): AudioRecord? {
        for (source in SOURCES) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    SIGNATURE_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 4,
                )
            }.getOrNull() ?: continue

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Microphone opened with source $source")
                return recorder
            }
            recorder.release()
        }
        return null
    }

    /**
     * Listens without ever stopping, emitting one window of audio after another.
     *
     * [record] opens the microphone, takes its twelve seconds and closes it, and the caller then
     * spends a second or two fingerprinting and asking Shazam before opening it again. Every one of
     * those seconds is deaf, and a track that changes during one is missed until the pass after. For
     * a device left listening on a table that is the entire job, so the recorder is opened once here
     * and windows are cut from the running stream instead.
     *
     * The collector's work overlaps the next window, because AudioRecord keeps filling its buffer
     * whether anyone reads it or not. A slow collector costs freshness, never coverage.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    fun stream(
        seconds: Int = DEFAULT_SECONDS,
        onProgress: (level: Float) -> Unit = {},
    ): Flow<ShortArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            SIGNATURE_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "16 kHz mono capture is unavailable on this device" }
        val recorder = open(minBuffer) ?: throw IllegalStateException("Could not open the microphone")

        val windowSize = SIGNATURE_SAMPLE_RATE_HZ * seconds
        try {
            recorder.startRecording()
            val chunk = ShortArray(CHUNK_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val window = ShortArray(windowSize)
                var written = 0
                while (written < windowSize && currentCoroutineContext().isActive) {
                    val read = recorder.read(chunk, 0, minOf(chunk.size, windowSize - written))
                    if (read <= 0) {
                        Log.w(TAG, "AudioRecord.read returned $read, ending the stream")
                        return@flow
                    }
                    chunk.copyInto(window, written, 0, read)
                    written += read
                    var peak = 0
                    for (i in 0 until read) peak = maxOf(peak, abs(chunk[i].toInt()))
                    onProgress(peak / 32768f)
                }
                if (written == windowSize) emit(window)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            Log.i(TAG, "Microphone stream closed")
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "MicrophoneListener"
        const val DEFAULT_SECONDS = 12
        private const val CHUNK_SAMPLES = 1600     // a tenth of a second

        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
    }
}
