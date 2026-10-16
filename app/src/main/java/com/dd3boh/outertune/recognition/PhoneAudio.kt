/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's own sound as what the recogniser listens to, instead of the microphone: the song in
 * a video or in another app, taken from Android's playback (AudioPlaybackCapture, Android 10 and
 * later), the way Google's song search does it (his idea, 16 Oct 2026). It works with headphones
 * in and in a loud room, where the microphone hears nothing useful.
 *
 * Android gives it only through a media projection, the permission screen recorders use: the user
 * answers "Start recording or casting?" for each listen (Android 14 and later ask every time), and
 * a foreground service of the mediaProjection type holds it while the listen runs
 * ([RecognitionService]). Only sound is taken, never the screen. An app can refuse to be captured
 * (allowAudioPlaybackCapture), and its sound is then simply not there.
 *
 * Main thread for [attach] and [release]; [projection] is read from the recording thread.
 */
object PhoneAudio {
    private const val TAG = "PhoneAudio"

    private val _projection = MutableStateFlow<MediaProjection?>(null)

    /** The projection a listen of the phone's own sound runs on, or null for the microphone. */
    val projection: StateFlow<MediaProjection?> = _projection

    /**
     * What is taken: what a video, a music app or a game plays. Not calls, alarms, notifications
     * or the assistant, which are none of a song's business.
     */
    val USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)

    /**
     * Set while a listen of the phone's own sound waits for its projection: the service is up
     * before the engine starts, and must not take the engine's not running for a run that is over.
     */
    @Volatile
    var awaitingStart = false

    /**
     * Seconds a window of the phone's own sound: about four is enough for Shazam on clean sound,
     * and a song in a short video lasts about ten before the video loops or is scrolled away.
     */
    const val WINDOW_SECONDS = 4

    /** Windows that named nothing before a listen of the phone gives up: twenty seconds in all. */
    const val MAX_MISSES = 5

    /** Whether this Android can listen to its own playback at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun attach(projection: MediaProjection) {
        release()
        // Android stops a projection by itself (the user ends it from the status bar, another app
        // takes the screen): the recorder then reads silence, and the listen ends on its own.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (_projection.value === projection) _projection.value = null
                Log.i(TAG, "The projection was stopped")
            }
        }, Handler(Looper.getMainLooper()))
        _projection.value = projection
        Log.i(TAG, "Listening to this phone's own sound from now on")
    }

    fun release() {
        val held = _projection.value ?: return
        _projection.value = null
        runCatching { held.stop() }
        Log.i(TAG, "Back to the microphone")
    }

    /**
     * A recorder on the phone's own playback, in the fingerprinter's shape, or null when Android
     * will not give one. Our own playback is left out: a song InterTune is playing is known already.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun recorder(context: Context, projection: MediaProjection, sampleRate: Int, bufferBytes: Int): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            USAGES.forEach { addMatchingUsage(it) }
            excludeUid(context.applicationInfo.uid)
        }.build()
        return runCatching {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.onFailure { Log.w(TAG, "No recorder on the phone's playback", it) }
            .getOrNull()
            ?.takeIf {
                (it.state == AudioRecord.STATE_INITIALIZED).also { ok -> if (!ok) it.release() }
            }
    }
}
