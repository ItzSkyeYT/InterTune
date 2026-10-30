/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.annotation.SuppressLint
import com.dd3boh.outertune.search.SystemSearch

/**
 * Keeps the microphone alive while the screen is off.
 *
 * The point of continuous recognition is a device left face down on a table filling a playlist by
 * itself, and none of that works from an Activity. Android stops a background process recording the
 * moment it loses foreground importance, so listening has to be a foreground service with the
 * microphone type, which in turn means a notification the user can see and stop. That notification
 * is not decoration; it is the price of the permission, and it is right that somebody can always
 * tell when an app is listening to a room.
 *
 * The service owns nothing but the lifetime. [RecognitionEngine] holds the loop and the state, so
 * the sheet can come and go without interrupting a run.
 */
@AndroidEntryPoint
class RecognitionService : Service() {

    @Inject lateinit var engine: RecognitionEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * A listen the Song on this phone tile started: nobody is looking at a screen of ours, so what
     * it finds, or that it found nothing, comes as a notification (PhoneSongTileService).
     */
    private var headless = false

    /**
     * A listen of the phone's own sound, from the tile or from the screen. The screen's sends
     * somebody off to the video's app, so its answer comes as a notification too while they are
     * there (his question of 16 Oct 2026, "where's the listening notification??").
     */
    private var phoneListen = false

    /** Whether this listen of the phone has had its answer posted, or was stopped by hand. */
    private var answered = false

    private var songWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Stopping is its own collector so that it is prompt. It used to be a check at the top of
        // the redraw loop, which tied how quickly the service goes away to how often it redraws,
        // and the redraw interval is about to stop being one second.
        scope.launch {
            // Not while a listen of the phone's own sound is waiting for its projection: the
            // engine starts only once Android has handed it over (PhoneAudio.awaitingStart).
            engine.running.collect { running ->
                if (!running && !PhoneAudio.awaitingStart) {
                    if (phoneListen && !answered && (headless || !appInFront())) notifyNothing()
                    stopSelf()
                }
            }
        }

        // Redrawn on a timer rather than only on events, because the progress bar has to move
        // between recognitions. The position is arithmetic on the last match, not a new request,
        // so a redraw is cheap, but cheap once a second for hours is not cheap: a continuous run
        // has no timeout and stops only when somebody stops it, so this was waking the process
        // 3600 times an hour for as long as it listened.
        //
        // With the screen off nobody is looking at the progress bar, and the only thing the timer
        // has to do is exist. So it drops to a slow tick and the notification is redrawn on the
        // way back: isInteractive is a cheap read, and the first pass after the screen comes on
        // repaints before anybody has focused on it.
        scope.launch {
            val power = getSystemService(PowerManager::class.java)
            while (engine.running.value) {
                val watching = power?.isInteractive != false
                if (watching) redraw()
                delay(if (watching) 1000L else 30_000L)
            }
        }

        // And straight away when the song changes or comes down, whatever the screen is doing.
        // The timer skips its redraw with the screen off and then sleeps for thirty seconds, so the
        // phone could be picked up to a song the engine had already dropped, still showing its
        // progress. Keyed on what the notification says rather than on every fresh estimate, so a
        // long run costs a redraw or two per song and not one per listen window. The first value is
        // skipped because onStartCommand draws it.
        scope.launch {
            engine.nowPlaying
                .map { it?.let { playing -> Triple(playing.title, playing.artist, playing.durationSeconds) } }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (engine.running.value) redraw() }
        }
        // The count under it, for the same reason.
        scope.launch {
            combine(engine.added, engine.recognised) { added, heard -> added.size to heard.size }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (engine.running.value) redraw() }
        }
        // And Shazam no longer answering, or answering again, which is the first thing somebody
        // picking the phone up needs to know about a run.
        scope.launch {
            engine.retryAt.drop(1).collect { if (engine.running.value) redraw() }
        }
    }

    /**
     * Posting needs POST_NOTIFICATIONS from API 33. Without it the update is simply dropped, which
     * is correct: the service is already foreground and the person refused to be told about it.
     */
    private fun redraw() {
        val notifier = NotificationManagerCompat.from(this)
        if (notifier.areNotificationsEnabled()) {
            notifier.notify(NOTIFICATION_ID, build())
        }
    }

    /**
     * Posts the song a listen of the phone found, when nobody is looking at one of our screens to
     * see it there: always for the tile's ([headless]), and for the screen's once somebody has gone
     * to the video's app. See [phoneListen].
     */
    private fun watchForTheSong() {
        songWatch?.cancel()
        songWatch = scope.launch {
            engine.state.collect { state ->
                if (phoneListen && !answered && state is RecognitionEngine.State.Found) {
                    answered = true
                    // The phone's sound is let go before the song is shown: the recording chip
                    // leaves the status bar as the song arrives, and nothing is taken longer than
                    // the song needed.
                    PhoneAudio.release()
                    if (headless || !appInFront()) notifyFound(state.track, state.searchWords)
                }
            }
        }
    }

    /** Whether one of the app's screens is showing, where a song found is on show already. */
    private fun appInFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    // The tile's listen checks RECORD_AUDIO itself right before it starts the engine.
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Any ACTION_STOP, which is the notification button and RecognitionViewModel.stop
            // alike. Naming the notification sent me hunting a phantom tap.
            Log.i(TAG, "Stop requested")
            // Stopped by hand is not "found nothing": no notification says so.
            answered = true
            engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        // The user's yes to "Start recording or casting?", when the listen is of the phone's own
        // sound (PhoneAudio). The service has to be in the foreground with the mediaProjection type
        // before Android hands the projection over, and with that type only once it was said yes to.
        val consent = consentOf(intent)
        // Known before the notification is drawn, which says which sound it listens to.
        phoneListen = consent != null
        if (consent == null) headless = false
        val notification = build()
        // The microphone type exists from Android 11. On 10 the two-argument call already takes the
        // types declared in the manifest, so nothing changes there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (consent != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (consent != null && PhoneAudio.supported) {
            val projection = runCatching {
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(consent.first, consent.second)
            }.onFailure { Log.w(TAG, "Android gave no projection", it) }.getOrNull()
            if (projection != null) PhoneAudio.attach(projection) else PhoneAudio.release()
            headless = intent?.getBooleanExtra(EXTRA_HEADLESS, false) == true
            phoneListen = projection != null
            answered = false
            if (phoneListen) watchForTheSong()
            // The tile's listen starts here, nobody's screen being there to start it: one listen
            // of the clip, its answer a notification.
            if (headless) {
                if (projection != null &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    engine.start(null, continuous = false)
                }
                PhoneAudio.awaitingStart = false
                if (projection == null) stopSelf()
            }
        }
        // Not sticky on purpose. If the system kills this, silently reopening the microphone later
        // without the user asking is exactly the behaviour nobody wants from a listening feature.
        return START_NOT_STICKY
    }

    private fun build(): android.app.Notification {
        val playing = engine.nowPlaying.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecognitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // A playlist's run adds to it. The screen's run adds to no playlist and lists what it heard
        // instead, where "Nothing added yet" sat under a list of songs for the whole run.
        val counted = if (engine.addsToPlaylist) {
            val added = engine.added.value.size
            if (added == 0) getString(R.string.recognition_service_none)
            else resources.getQuantityString(R.plurals.recognition_service_added, added, added)
        } else {
            val heard = engine.recognised.value.size
            if (heard == 0) getString(R.string.recognition_service_none_heard)
            else resources.getQuantityString(R.plurals.recognition_service_heard, heard, heard)
        }

        // Keep listening that Shazam has stopped answering says so, where it used to look exactly
        // like a run listening to a quiet room. The same words as the screen's.
        val waiting = engine.retryAt.value?.takeIf { engine.continuous.value }?.let { retryAt ->
            val seconds = ShazamBackoff.secondsLeft(retryAt, System.currentTimeMillis())
            if (seconds > 0) resources.getQuantityString(R.plurals.recognition_shazam_waiting, seconds, seconds)
            else getString(R.string.recognition_shazam_retrying)
        }

        // A listen of the phone's own sound shows itself once, briefly and without a sound, over the
        // video it listens to: the panel and Android's question are gone by then, and nothing else
        // would say that it started (his "where's the listening notification??", 16 Oct 2026).
        val phone = phoneListen || PhoneAudio.projection.value != null
        val builder = NotificationCompat.Builder(this, if (phone) PHONE_CHANNEL_ID else CHANNEL_ID)
            // The sound bars, as everywhere song recognition is (his word of 16 Oct 2026).
            .setSmallIcon(R.drawable.ic_recognise)
            .setContentIntent(open)
            .addAction(0, getString(R.string.recognition_service_stop), stop)
            .setOngoing(true)
            .setSilent(!phone)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (playing == null) {
            builder.setContentTitle(
                getString(if (phone) R.string.recognition_service_title_phone else R.string.recognition_service_title)
            )
            if (waiting != null) builder.setContentText(waiting).setSubText(counted)
            else builder.setContentText(counted)
            return builder.build()
        }

        val position = playing.positionSeconds()
        builder
            .setContentTitle(listOfNotNull(playing.title, playing.artist).joinToString(" - "))
            .setSubText(counted)

        val duration = playing.durationSeconds
        if (duration != null && duration > 0) {
            // Where the room is in the song, not where this app is: nothing here is playing it.
            builder
                .setContentText("${time(position)} / ${time(duration)}")
                .setProgress(duration, position.coerceAtMost(duration), false)
        } else {
            builder.setContentText(getString(R.string.recognition_service_title))
        }
        // In place of the position, and the bar stays: the estimate runs on without Shazam.
        waiting?.let { builder.setContentText(it) }
        return builder.build()
    }

    /**
     * The song the tile's listen found: its name, its artist, Shazam's cover when it comes in
     * time, and a tap that opens InterTune's search for it, to play it or add it to a playlist.
     * The search is for the words that found it ([words], RecognitionEngine.State.Found's
     * searchWords): his reel heard as "Lush Life (Riot Remix)", a version YouTube Music lists no
     * song of, opened on other people's uploads and covers, and Zara Larsson's own was not there.
     */
    private fun notifyFound(track: Recognised, words: String) {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SystemSearch.searchLink(words)), this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Away from the main thread for the cover, which may not come at all: then the plain one.
        CoroutineScope(Dispatchers.IO).launch {
            val cover = track.artworkUrl?.let { url ->
                runCatching {
                    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 2000
                        readTimeout = 2000
                        inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                }.getOrNull()
            }
            val notification = NotificationCompat.Builder(this@RecognitionService, RESULTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recognise)
                .setContentTitle(track.title)
                .setContentText(listOfNotNull(track.artist, getString(R.string.recognition_phone_found_tap)).joinToString(" · "))
                .setLargeIcon(cover)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            post(notification)
        }
    }

    /** The tile's listen ended without a song. Quiet, and gone when tapped. */
    private fun notifyNothing() {
        val notification = NotificationCompat.Builder(this, RESULTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recognise)
            .setContentTitle(getString(R.string.recognition_phone_nothing))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(nothingHint())))
            .setContentText(getString(nothingHint()))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        post(notification)
    }

    /** Why nothing was found, as far as the engine knows: silence has its own likely reasons. */
    private fun nothingHint(): Int =
        if ((engine.state.value as? RecognitionEngine.State.Failed)?.heardNothing == true) R.string.recognition_phone_heard_nothing
        else R.string.recognition_phone_nothing_hint

    @SuppressLint("MissingPermission")
    private fun post(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(RESULTS_CHANNEL_ID) == null) {
                // Its own channel, heard: a song found while scrolling is worth a sound and a
                // banner, where the listening notification above it is silent.
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        RESULTS_CHANNEL_ID, getString(R.string.recognition_phone_results_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
        }
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(RESULT_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the song found on this phone", it) }
    }

    private fun time(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun createChannel() {
        // Channels arrived in API 26 and this app still runs on 24, where every call below throws.
        // NotificationManagerCompat posts fine without one.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recognition_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        // Seen, not heard: it pops up over the video once, with no sound to land in the video's.
        if (manager.getNotificationChannel(PHONE_CHANNEL_ID) == null) manager.createNotificationChannel(
            NotificationChannel(
                PHONE_CHANNEL_ID,
                getString(R.string.recognition_service_title_phone),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        // The projection lives no longer than the listen: the next one asks again, as Android
        // wants from 14 on, and the status bar's recording sign goes with it.
        PhoneAudio.release()
        super.onDestroy()
    }

    /** The result of the consent screen, put in the start intent by [startOnPhone], or null. */
    private fun consentOf(intent: Intent?): Pair<Int, Intent>? {
        intent ?: return null
        if (!intent.hasExtra(EXTRA_CONSENT_CODE)) return null
        val data = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT_DATA, Intent::class.java)
            ?: return null
        return intent.getIntExtra(EXTRA_CONSENT_CODE, 0) to data
    }

    companion object {
        private const val TAG = "RecognitionService"
        private const val CHANNEL_ID = "song_recognition"
        private const val PHONE_CHANNEL_ID = "song_recognition_phone"
        private const val NOTIFICATION_ID = 4243
        const val ACTION_STOP = "com.dd3boh.outertune.recognition.STOP"
        private const val EXTRA_CONSENT_CODE = "consent_code"
        private const val EXTRA_CONSENT_DATA = "consent_data"
        private const val EXTRA_HEADLESS = "headless"
        private const val RESULTS_CHANNEL_ID = "song_recognition_results"
        private const val RESULT_NOTIFICATION_ID = 4244

        fun start(context: Context) {
            val intent = Intent(context, RecognitionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * As [start], listening to the phone's own sound with the consent screen's [resultCode] and
         * [data]. [headless] is the tile's listen, started and answered here: see [headless].
         */
        fun startOnPhone(context: Context, resultCode: Int, data: Intent, headless: Boolean = false) {
            val intent = Intent(context, RecognitionService::class.java)
                .putExtra(EXTRA_CONSENT_CODE, resultCode)
                .putExtra(EXTRA_CONSENT_DATA, data)
                .putExtra(EXTRA_HEADLESS, headless)
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
