/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.fingerprint.SIGNATURE_SAMPLE_RATE_HZ
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
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
class MicrophoneListener @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * One window of audio and the wall-clock time its first sample was heard.
     *
     * The time travels with the samples because nothing downstream can work it out. Shazam's
     * offset is where the start of the window falls in the track, and the engine only receives a
     * window once all of it has been recorded, later still when the previous one was slow to
     * identify. Timing it from when the answer came back put every position estimate a whole
     * window and a network round trip behind the room.
     */
    class Window(val samples: ShortArray, val startedAtMs: Long)

    /**
     * Debug builds only: the recording standing in for the room has run out, which ends [stream].
     *
     * Thrown rather than ending the stream quietly, so that the engine puts the run down as if stop
     * had been pressed: a recording played to its end is a run that is over. A class of its own
     * because the microphone throws too, when a read fails, and that the engine reports as a
     * failure. Only once the collector has had every window: see [stream].
     */
    class RecordingEnded : Exception("The stand-in recording has ended")

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
        // The same stand-in as Keep listening's, its first window, as long as a listen would take.
        // All of it when it is shorter than that, as a stop partway through a listen would be.
        debugRoom()?.let { room ->
            val started = System.currentTimeMillis()
            return@withContext replay(room, seconds, partial = true) { level -> onProgress(System.currentTimeMillis() - started, level) }
                .firstOrNull()?.samples ?: ShortArray(0)
        }
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
     * whether anyone reads it or not. A collector slower than the windows misses some rather than
     * falling behind the room: see [freshWindows].
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    fun stream(
        seconds: Int = DEFAULT_SECONDS,
        onProgress: (level: Float) -> Unit = {},
    ): Flow<Window> = flow {
        val room = withContext(Dispatchers.IO) { debugRoom() }
        if (room == null) return@flow emitAll(listen(seconds, onProgress))
        // Read on the IO thread, ahead of the collector, as the microphone is, and the end thrown
        // here, on the collector's side, once it has handled the last window. Thrown where the
        // recording is read, it cancelled the collector at once, and the window being identified
        // went with it: the last one, whenever the recording ran out less than an identify after it.
        emitAll(replay(room, seconds, onProgress = onProgress).flowOn(Dispatchers.IO))
        // Played to its end. A stop is a cancellation, which threw on the way here or throws now.
        currentCoroutineContext().ensureActive()
        throw RecordingEnded()
    }

    /** The microphone's side of [stream]: the recorder opened once, windows cut from it until stopped. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    private fun listen(seconds: Int, onProgress: (level: Float) -> Unit): Flow<Window> = flow {
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
                        // Thrown, not returned. Returning completed the stream as if it had been
                        // stopped, so a dead audio server or a microphone that went away ended Keep
                        // listening with no message at all; the engine only reports a failure it
                        // is told about. The finally below still releases the recorder.
                        Log.w(TAG, "AudioRecord.read returned $read, ending the stream")
                        throw java.io.IOException("AudioRecord.read returned $read")
                    }
                    chunk.copyInto(window, written, 0, read)
                    written += read
                    var peak = 0
                    for (i in 0 until read) peak = maxOf(peak, abs(chunk[i].toInt()))
                    onProgress(peak / 32768f)
                }
                // Worked back from the end. The last read returns as soon as its tenth of a second
                // has been captured, so this moment is within a chunk of the final sample, and the
                // window is a fixed length of audio before it.
                if (written == windowSize) {
                    emit(Window(window, System.currentTimeMillis() - seconds * 1000L))
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            Log.i(TAG, "Microphone stream closed")
        }
    }.freshWindows().flowOn(Dispatchers.IO)

    /**
     * Debug builds only: a recording standing in for the room, so Keep listening can be run end to
     * end on an emulator, which has no microphone, against real Shazam and real YouTube searches.
     * 16 kHz mono signed 16-bit little-endian, the fingerprinter's own shape, as room.pcm in the
     * app's own files, where run-as can put it (a file pushed to Android/data belongs to the shell
     * and the app cannot read it):
     *
     *     ffmpeg -i song.mp3 -ac 1 -ar 16000 -f s16le room.pcm
     *     adb shell run-as dev.skye.intertune.debug sh -c 'cat > files/room.pcm' < room.pcm
     *
     * Delete it (run-as ... rm files/room.pcm) to have the microphone back. A release build never looks.
     */
    private fun debugRoom(): File? {
        if (!BuildConfig.DEBUG) return null
        val file = File(context.filesDir, "room.pcm")
        return file.takeIf { runCatching { it.canRead() && it.length() > 0 }.getOrDefault(false) }
    }

    /**
     * [room] window by window at the pace of the clock, as the microphone would hear it, until it
     * ends. What is left at the end, short of a whole window, is dropped, as the microphone drops
     * the part window it was in when stopped, unless [partial]: then it comes as one last, shorter
     * window. [record] wants that: for a recording shorter than a window it is all there is, and
     * dropping it made [record] come back empty.
     */
    private fun replay(
        room: File,
        seconds: Int,
        partial: Boolean = false,
        onProgress: (level: Float) -> Unit,
    ): Flow<Window> = flow {
        Log.i(TAG, "Debug: listening to ${room.path} instead of the microphone")
        val windowSize = SIGNATURE_SAMPLE_RATE_HZ * seconds
        val bytes = ByteArray(CHUNK_SAMPLES * 2)
        room.inputStream().buffered().use { input ->
            while (currentCoroutineContext().isActive) {
                val window = ShortArray(windowSize)
                var written = 0
                fill@ while (written < windowSize) {
                    val want = minOf(bytes.size, (windowSize - written) * 2)
                    var read = 0
                    while (read < want) read += input.read(bytes, read, want - read).takeIf { it > 0 } ?: break
                    if (read < 2) break@fill
                    var peak = 0
                    for (i in 0 until read / 2) {
                        val sample = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
                        window[written + i] = sample
                        peak = maxOf(peak, abs(sample.toInt()))
                    }
                    written += read / 2
                    onProgress(peak / 32768f)
                    delay(read / 2 * 1000L / SIGNATURE_SAMPLE_RATE_HZ)
                }
                if (written == windowSize) {
                    emit(Window(window, System.currentTimeMillis() - seconds * 1000L))
                    continue
                }
                Log.i(TAG, "Debug: the recording has ended")
                // Timed from its own length, not a whole window's.
                if (partial && written > 0) {
                    emit(Window(window.copyOf(written), System.currentTimeMillis() - written * 1000L / SIGNATURE_SAMPLE_RATE_HZ))
                }
                return@flow
            }
        }
    }

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

/**
 * Windows as the engine should get them: the newest one waiting at most, never a queue.
 *
 * The microphone records on while a window is being identified, and flowOn on its own holds up to
 * 64 windows for a collector that falls behind, thirteen minutes of audio and 25 MB of it at the
 * usual twelve seconds. A request that hangs until it times out takes longer than a window, so a
 * run on a network like that fell further behind with every window, and when the network came back
 * it had the whole backlog to get through, minutes stale, asking Shazam about each window in a burst,
 * which is what gets a phone refused in the first place. Now a window nobody got to is dropped for
 * the one after it. The engine takes that as a gap, which it already allows for: every window carries
 * the time it was heard.
 */
internal fun <T> Flow<T>.freshWindows(): Flow<T> = conflate()
