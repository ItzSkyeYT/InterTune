/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecapNotesToldKey
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * "A note from your past self is waiting" (his answer of 22 Nov 2026: "a notification with highest
 * priority"): once a year, when that year's Recap is out (RecapCalendar) and notes were sealed for
 * it (RecapNote), a heads-up notification on its own channel; a tap opens the Recap. Checked as the
 * app starts and by a worker on the reveal day's morning, so it comes even if the app is not
 * opened that day. Told once per year (RecapNotesToldKey); with no notes, or without Android 13's
 * permission to post, nothing.
 */
object RecapNoteAlert {
    const val CHANNEL_ID = "recap_notes"
    private const val NOTIFICATION_ID = 4291
    private const val WORK_NAME = "recap_notes_alert"
    private const val TAG = "RecapNoteAlert"

    /** On the tap: the Recap of this year opens (MainActivity, RecapDoor). */
    const val EXTRA_OPEN_RECAP = "recap_notes_open_recap"

    /** The hour of the reveal day the worker looks, so the notification does not come in the night. */
    private const val MORNING = 10

    /**
     * The Recap whose notes are to be told about, and how many there are: the Recap on show
     * ([onShow], RecapCalendar.yearOnShow), not told about yet ([toldYear], 0 for none), with notes
     * sealed for it, those kept under the year before ([counts], RecapNote.years). Null otherwise.
     */
    fun due(onShow: Int?, toldYear: Int, counts: Map<Int, Int>): Pair<Int, Int>? {
        val recap = onShow ?: return null
        if (toldYear >= recap) return null
        val count = counts[recap - 1] ?: 0
        return if (count > 0) recap to count else null
    }

    /** Looks once and tells if it is due, remembering it was told. Off the main thread. */
    suspend fun check(context: Context) {
        val prefs = context.dataStore.data.first()
        val onShow = RecapCalendar.yearOnShow(LocalDate.now(), prefs[RecapRevealedEarlyKey] == true && BuildConfig.PREVIEW_FEATURES)
        val (recap, count) = due(onShow, prefs[RecapNotesToldKey] ?: 0, RecapNote.years(context)) ?: return
        if (post(context, recap, count)) context.dataStore.edit { it[RecapNotesToldKey] = recap }
    }

    /** The worker for the next reveal day's morning, kept by WorkManager through a restart of the phone. */
    fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        var at = RecapCalendar.revealDay(now.year).atTime(MORNING, 0)
        if (!at.isAfter(now)) at = RecapCalendar.revealDay(now.year + 1).atTime(MORNING, 0)
        val request = OneTimeWorkRequestBuilder<RecapNoteAlertWorker>()
            .setInitialDelay(Duration.between(now, at).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request) }
            .onFailure { Log.w(TAG, "Could not plan the look on the reveal day", it) }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, recap: Int, count: Int): Boolean {
        val notifier = NotificationManagerCompat.from(context)
        if (!notifier.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Made each time, so its name in Android's settings is in the app's language now (ChannelNames).
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.recap_notes_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_RECAP, recap).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.resources.getQuantityString(R.plurals.recap_notes_waiting, count, count))
            .setContentText(context.getString(R.string.recap_notes_waiting_line, recap.toString()))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return runCatching { notifier.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not tell about the notes", it) }
            .isSuccess
    }
}

/** The reveal day's look (RecapNoteAlert.schedule): tells if it is due, then plans next year's. */
class RecapNoteAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { RecapNoteAlert.check(applicationContext) }
        RecapNoteAlert.schedule(applicationContext)
        return Result.success()
    }
}
