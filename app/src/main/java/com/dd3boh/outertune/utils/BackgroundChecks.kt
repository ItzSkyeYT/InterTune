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
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.BackgroundCheckHoursKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
                    if (update != null) {
                        notify(
                            context,
                            UPDATE_NOTIFICATION_ID,
                            context.getString(R.string.background_update_title),
                            context.getString(R.string.background_update_text, update.versionName),
                        )
                    }
                }
                .onFailure { Log.w(TAG, "Update check failed", it) }
        }

        if (context.dataStore.get(PollsEnabledKey, false)) {
            runCatching { deps.pollChecker().check() }
                .onSuccess { poll ->
                    if (poll != null) {
                        notify(
                            context,
                            POLL_NOTIFICATION_ID,
                            context.getString(R.string.background_poll_title),
                            poll.banner,
                        )
                    }
                }
                .onFailure { Log.w(TAG, "Poll check failed", it) }
        }

        return Result.success()
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Nothing to be done about it from a worker, and the badge in Settings still shows.
            Log.i(TAG, "No notification permission, skipping")
            return
        }

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

        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java),
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
                .build()
        )
        Log.i(TAG, "Notified: $title")
    }

    companion object {
        private const val TAG = "BackgroundCheck"
        private const val CHANNEL_ID = "background_checks"
        private const val WORK_NAME = "background_checks"
        private const val UPDATE_NOTIFICATION_ID = 4244
        private const val POLL_NOTIFICATION_ID = 4245

        /** What the setting offers. 0 is off, and is the default. */
        val INTERVAL_CHOICES = listOf(0, 1, 2, 5, 10, 24)

        /**
         * Applies the current setting, replacing whatever was scheduled before.
         *
         * Safe to call on every launch and on every change: the work is keyed by name and the
         * existing schedule is replaced rather than stacked.
         */
        fun schedule(context: Context, hoursOverride: Int? = null) {
            // The caller may pass the value it has just chosen. Re-reading the datastore here was
            // the first version and it was wrong: the preference setter is fire and forget, so a
            // schedule call straight after it reads the old value and the setting appeared to do
            // nothing at all. The same trap PollsOptInCard documents.
            val hours = hoursOverride ?: context.dataStore.get(BackgroundCheckHoursKey, 0)
            val manager = WorkManager.getInstance(context)

            if (hours <= 0) {
                manager.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Background checks off")
                return
            }

            val request = PeriodicWorkRequestBuilder<BackgroundCheckWorker>(
                hours.toLong(), TimeUnit.HOURS
            ).setConstraints(
                // No point waking to make two requests that cannot be made.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()

            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(TAG, "Background checks every ${hours}h")
        }
    }
}
