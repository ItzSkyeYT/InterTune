/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.fingerprint.SignatureGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listening, as a foreground service rather than as work the screen owns.
 *
 * This is not architecture for its own sake. From Android 9 an app that is not in the foreground
 * gets silence from the microphone rather than an error, so listening from a composable would
 * quietly stop producing anything the moment the screen went off, and look for all the world like a
 * room that had gone quiet. A foreground service is the only way the platform will keep handing
 * over audio, which is what makes "start it and put the phone down" possible at all.
 *
 * State lives in the companion rather than behind a binder. There is one of these at a time, the
 * screen is the only reader, and a binder would be three files of ceremony for a value that is
 * already a flow.
 */
class RecognitionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listening: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                return START_NOT_STICKY
            }

            ACTION_START_ONCE -> start(continuous = false)
            ACTION_START_CONTINUOUS -> start(continuous = true)
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun start(continuous: Boolean) {
        // Restarting while already listening would put two recorders on one microphone, and the
        // second one loses. The request is simply ignored.
        if (listening?.isActive == true) return

        startForegroundSafely()
        _continuous.value = continuous
        _state.value = RecognitionState.Listening

        listening = scope.launch {
            do {
                val outcome = identifyOnce()
                if (outcome is RecognitionResult.Match) {
                    // Newest first, and never the same song twice in a row. A continuous listen
                    // through one three minute song would otherwise fill the list with it.
                    val seen = _history.value
                    if (seen.firstOrNull()?.sameAs(outcome) != true) {
                        _history.value = listOf(outcome) + seen
                    }
                } else if (!continuous && outcome is RecognitionResult.Failed) {
                    _state.value = RecognitionState.Failed(outcome.reason)
                    stopListening()
                    return@launch
                }

                // A gap between attempts. Back to back recognitions of a song that is still
                // playing cost requests and battery to learn nothing, and the ear needs a moment
                // anyway.
                if (continuous) delay(GAP_BETWEEN_ATTEMPTS_MS)
            } while (continuous && isActive)

            stopListening()
        }
    }

    private suspend fun identifyOnce(): RecognitionResult = withContext(Dispatchers.Default) {
        val samples = MicrophoneSnippet.record { _level.value = it }
            ?: return@withContext RecognitionResult.Failed(getString(R.string.recognise_no_mic))
        _level.value = 0f

        val signature = SignatureGenerator.makeSignature(samples)
        if (signature.peaksByBand.sumOf { it.size } == 0) return@withContext RecognitionResult.NoMatch

        ShazamClient.identify(signature, samples.size)
    }

    private fun stopListening() {
        listening?.cancel()
        listening = null
        _continuous.value = false
        _level.value = 0f
        if (_state.value is RecognitionState.Listening) _state.value = RecognitionState.Idle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun startForegroundSafely() {
        ensureChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecognitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.recognise))
            .setContentText(getString(R.string.recognise_listening))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.recognise_stop),
                            stop,
                        ).build()
                    )
                }
            }
            .build()

        // The microphone type is what the platform checks before it will keep feeding audio to a
        // backgrounded process. Declared from Android 10, required from 14.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Android 14 refuses the microphone type outright if the permission is not granted.
            // Nothing useful can be done from here; the screen already shows why.
            Log.w(TAG, "Could not go foreground", it)
            _state.value = RecognitionState.Failed(getString(R.string.recognise_no_mic))
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recognise),
            // LOW: this says what the phone is doing, it is not news.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        listening?.cancel()
        scope.cancel()
        _continuous.value = false
        if (_state.value is RecognitionState.Listening) _state.value = RecognitionState.Idle
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecognitionService"
        const val CHANNEL_ID = "recognition"

        /** Must not collide with the media notification, the downloads one or the sleep timer. */
        private const val NOTIFICATION_ID = 4243

        private const val GAP_BETWEEN_ATTEMPTS_MS = 3_000L

        const val ACTION_START_ONCE = "com.dd3boh.outertune.RECOGNISE_ONCE"
        const val ACTION_START_CONTINUOUS = "com.dd3boh.outertune.RECOGNISE_CONTINUOUS"
        const val ACTION_STOP = "com.dd3boh.outertune.RECOGNISE_STOP"

        private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
        val state: StateFlow<RecognitionState> = _state.asStateFlow()

        private val _level = MutableStateFlow(0f)

        /** How loud the room is right now, 0 to 1, while listening. */
        val level: StateFlow<Float> = _level.asStateFlow()

        private val _continuous = MutableStateFlow(false)

        /** True while the listen is the kind that keeps going until it is stopped. */
        val continuous: StateFlow<Boolean> = _continuous.asStateFlow()

        /**
         * What has been recognised, newest first.
         *
         * Deliberately not a table. This is a scratch list for the session: the songs worth keeping
         * are the ones the user plays or adds to a playlist, and everything else is noise that a
         * database would keep forever.
         */
        private val _history = MutableStateFlow<List<RecognitionResult.Match>>(emptyList())
        val history: StateFlow<List<RecognitionResult.Match>> = _history.asStateFlow()

        fun clearHistory() {
            _history.value = emptyList()
        }

        fun start(context: Context, continuous: Boolean) {
            val intent = Intent(context, RecognitionService::class.java)
                .setAction(if (continuous) ACTION_START_CONTINUOUS else ACTION_START_ONCE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecognitionService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/** Two songs are the same when Shazam says so, or failing that when they read the same. */
private fun RecognitionResult.Match.sameAs(other: RecognitionResult.Match): Boolean =
    if (key != null && other.key != null) key == other.key
    else title == other.title && artist == other.artist

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Listening : RecognitionState
    data class Failed(val reason: String) : RecognitionState
}
