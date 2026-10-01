/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.BackgroundCheckHoursKey
import com.dd3boh.outertune.constants.LastNotifiedPollIdKey
import com.dd3boh.outertune.constants.LastNotifiedUpdateCodeKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.constants.UpdateSnoozeUntilKey
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Looks for updates and questions while the app is closed, and says so once.
 *
 * Both checks already existed and both only ran when somebody opened the app, which is the wrong
 * moment: by then they are looking at the screen that would have told them anyway. This is the same
 * two requests on a schedule, with a notification as the point of it.
 *
 * One worker for both rather than two, because they are one promise to the user and two schedules
 * would mean waking the device twice to answer it.
 */
class BackgroundCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun updateChecker(): UpdateChecker
        fun pollChecker(): PollChecker
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)

        // Each half is gated on its own opt in, exactly as it is in the app. A background schedule
        // must not become a way to check something the user turned off.
        if (context.dataStore.get(UpdateCheckEnabledKey, false)) {
            runCatching { deps.updateChecker().check() }
                .onSuccess { update ->
                    // check() returns the same pending update on every run, fetched again or
                    // restored from disk, so without this every run notified again until the update
                    // was installed or dismissed. Remind me later is honoured here too.
                    if (update != null && shouldNotifyUpdate(
                            versionCode = update.versionCode,
                            lastNotifiedCode = context.dataStore.get(LastNotifiedUpdateCodeKey, -1),
                            snoozeUntil = context.dataStore.get(UpdateSnoozeUntilKey, 0L),
                            now = System.currentTimeMillis(),
                        )
                    ) {
                        val posted = notify(
                            context,
                            UPDATE_NOTIFICATION_ID,
                            context.getString(R.string.background_update_title),
                            context.getString(R.string.background_update_text, update.versionName),
                        )
                        // Recorded only once something was posted: without POST_NOTIFICATIONS
                        // notify() posts nothing, and a version recorded then would never be
                        // announced after the permission is granted.
                        if (posted) context.dataStore.edit { it[LastNotifiedUpdateCodeKey] = update.versionCode }
                    }
                }
                .onFailure { Log.w(TAG, "Update check failed", it) }
        }

        if (context.dataStore.get(PollsEnabledKey, false)) {
            runCatching { deps.pollChecker().check() }
                .onSuccess { poll ->
                    if (poll != null && shouldNotifyPoll(poll.id, context.dataStore.get(LastNotifiedPollIdKey, ""))) {
                        val posted = notify(
                            context,
                            POLL_NOTIFICATION_ID,
                            context.getString(R.string.background_poll_title),
                            poll.banner,
                            openPoll = true,
                        )
                        if (posted) context.dataStore.edit { it[LastNotifiedPollIdKey] = poll.id }
                    }
                }
                .onFailure { Log.w(TAG, "Poll check failed", it) }
        }

        return Result.success()
    }

    /** Whether it actually posted: false when there was no permission to post anything at all. */
    private fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        openPoll: Boolean = false,
    ): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Nothing to be done about it from a worker, and the badge in Settings still shows.
            Log.i(TAG, "No notification permission, skipping")
            return false
        }

        // Channels arrived in API 26 and this app still runs on 24, where every call in here
        // throws. NotificationCompat below posts fine without one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.background_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
        }

        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                // Tapping a notification about a question should land on the question, not on the
                // home screen with the banner somewhere below the fold.
                if (openPoll) putExtra(EXTRA_OPEN_POLL, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        NotificationManagerCompat.from(context).notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                // A newer version or question replaces the notification under the same id; this
                // keeps that replacement from sounding again while the old one is still in the
                // shade.
                .setOnlyAlertOnce(true)
                .build()
        )
        Log.i(TAG, "Notified: $title")
        return true
    }

    companion object {
        private const val TAG = "BackgroundCheck"
        private const val CHANNEL_ID = "background_checks"
        private const val WORK_NAME = "background_checks"
        /** Read by MainActivity to open the question rather than merely the app. */
        const val EXTRA_OPEN_POLL = "com.dd3boh.outertune.OPEN_POLL"
        private const val UPDATE_NOTIFICATION_ID = 4244
        private const val POLL_NOTIFICATION_ID = 4245

        /** What the setting offers. 0 is off, and is the default. */
        val INTERVAL_CHOICES = listOf(0, 1, 2, 5, 10, 24)

        /**
         * schedule() calls, applied one at a time in the order they were made. Off the main thread,
         * where every caller is, because finding out what is scheduled is a query of WorkManager's
         * database. In order, so that two quick picks in Settings end on the second.
         */
        private val scheduleCalls = Channel<suspend () -> Unit>(Channel.UNLIMITED).also { calls ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for (call in calls) {
                    try {
                        call()
                    } catch (e: Exception) {
                        // Nobody to hand it to, and one failed call must not stop the ones after it.
                        Log.w(TAG, "Could not apply the background check schedule", e)
                    }
                }
            }
        }

        /**
         * Applies the current setting: cancels the checks when it is off, schedules them when they
         * are not scheduled, and replaces the schedule only when it was made for another interval
         * ([backgroundCheckPolicy]).
         *
         * It used to replace the schedule on every call, and every launch calls it. In WorkManager
         * 2.8.1 replacing (ExistingPeriodicWorkPolicy.UPDATE) keeps the time of the last run but
         * resets the count of runs to zero, and a periodic run with a count of zero is due at the
         * time of the last one: in the past. So with the checks on, every launch ran one at once,
         * whatever the interval. AutoBackup.schedule had the same problem and has the same fix.
         *
         * Returns at once and applies shortly after, in order with the other calls.
         */
        fun schedule(context: Context, hoursOverride: Int? = null) {
            val appContext = context.applicationContext
            scheduleCalls.trySend { applySchedule(appContext, hoursOverride) }
        }

        private suspend fun applySchedule(context: Context, hoursOverride: Int?) {
            // The caller may pass the value it has just chosen. Re-reading the datastore here was
            // the first version and it was wrong: the preference setter is fire and forget, so a
            // schedule call straight after it reads the old value and the setting appeared to do
            // nothing at all. The same trap PollsOptInCard documents.
            val hours = hoursOverride ?: context.dataStore.data.first()[BackgroundCheckHoursKey] ?: 0
            val manager = WorkManager.getInstance(context)

            if (hours <= 0) {
                manager.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Background checks off")
                return
            }

            // A cancelled schedule stays listed until WorkManager clears it out, and is not one:
            // enqueueing over it starts afresh whatever the policy.
            val current = manager.getWorkInfosForUniqueWork(WORK_NAME).await().firstOrNull { !it.state.isFinished }
            val policy = backgroundCheckPolicy(current?.tags, hours)
            val request = PeriodicWorkRequestBuilder<BackgroundCheckWorker>(
                hours.toLong(), TimeUnit.HOURS
            ).setConstraints(
                // No point waking to make two requests that cannot be made.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).addTag(backgroundCheckTag(hours)).build()

            manager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
            Log.i(
                TAG,
                "Background checks every ${hours}h, " + when {
                    current == null -> "scheduled"
                    policy == ExistingPeriodicWorkPolicy.KEEP -> "already scheduled"
                    else -> "schedule replaced"
                }
            )
        }
    }
}

private const val BACKGROUND_CHECK_TAG = "background_checks_interval_hours="

/**
 * The tag the background check schedule carries, saying which interval it was made for, since
 * WorkManager 2.8.1 cannot say. Stored with the schedule, so changing its format replaces every
 * schedule once.
 */
internal fun backgroundCheckTag(hours: Int): String = "$BACKGROUND_CHECK_TAG$hours"

/**
 * How BackgroundCheckWorker.schedule enqueues the schedule for [hours], given the tags of the one
 * there now ([scheduledTags], null when there is none). KEEP when it was made for [hours], which is
 * every launch where nothing changed, since replacing makes a check due at once in 2.8.1. KEEP also
 * when there is none, where it simply enqueues. UPDATE when it was made for another interval, which
 * is a new pick in Settings or a Restore that brought another one back, or does not say, which is a
 * schedule from before the tag and is replaced once.
 */
internal fun backgroundCheckPolicy(scheduledTags: Collection<String>?, hours: Int): ExistingPeriodicWorkPolicy {
    if (scheduledTags == null) return ExistingPeriodicWorkPolicy.KEEP
    val scheduled = scheduledTags.firstNotNullOfOrNull { tag ->
        if (tag.startsWith(BACKGROUND_CHECK_TAG)) tag.removePrefix(BACKGROUND_CHECK_TAG).toIntOrNull() else null
    }
    return if (scheduled == hours) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
}

/**
 * Whether a background run should notify about this update: a version it has not already said so
 * about, and not while "remind me later" is still in effect. Kept apart from [BackgroundCheckWorker]
 * so the decision is tested without WorkManager or a real notification.
 */
internal fun shouldNotifyUpdate(versionCode: Int, lastNotifiedCode: Int, snoozeUntil: Long, now: Long): Boolean =
    versionCode != lastNotifiedCode && now >= snoozeUntil

/** Whether a background run should notify about this poll: one it has not already said so about. */
internal fun shouldNotifyPoll(pollId: String, lastNotifiedId: String): Boolean =
    pollId != lastNotifiedId
