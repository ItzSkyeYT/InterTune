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
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.constants.RecapToldKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * "Your 2026 InterTune Recap is here" (his ask of 22 Nov 2026: "recap as a notification", "with
 * highest priority"): once a year, when the year's Recap is out (RecapCalendar, the debug options'
 * early reveal included) in its season and holds enough music, as the pop-up does (RecapEntry), a
 * heads-up notification on its own channel; a tap opens the Recap. Its second line says how many
 * notes from your past self wait in it, when some do (RecapNote). Looked for as the app starts and
 * by a worker on the reveal day's morning, so it comes even if the app is not opened that day.
 * Told once a year (RecapToldKey), and a Recap opened here counts as told (RecapNotesOpen.opened);
 * without Android 13's permission to post, nothing, and it is looked for again next time.
 */
object RecapAlert {
    const val CHANNEL_ID = "recap"
    private const val NOTIFICATION_ID = 4291
    private const val WORK_NAME = "recap_alert"
    private const val TAG = "RecapAlert"

    /** On the tap: that year's Recap opens (MainActivity, RecapDoor). */
    const val EXTRA_OPEN_RECAP = "recap_open_recap"

    /** The hour of the reveal day the worker looks, so the notification does not come in the night. */
    private const val MORNING = 10

    /** What to tell: the Recap of [recap], and the notes from the year before that wait in it ([notes], 0 for none). */
    data class Due(val recap: Int, val notes: Int)

    /**
     * Whether to tell about the Recap on show ([onShow], RecapCalendar.yearOnShow), in its season
     * ([inSeason], RecapCalendar.onHome) and holding enough music ([enough]), not told about yet
     * ([toldYear], 0 for none): with how many notes were sealed for it, those kept under the year
     * before ([counts], RecapNote.years). Null otherwise.
     */
    fun due(onShow: Int?, inSeason: Boolean, enough: Boolean, toldYear: Int, counts: Map<Int, Int>): Due? {
        val recap = onShow ?: return null
        if (!inSeason || !enough || toldYear >= recap) return null
        return Due(recap, counts[recap - 1] ?: 0)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): MusicDatabase
    }

    /** Looks once and tells if it is due, remembering it was told. Off the main thread. */
    suspend fun check(context: Context, today: LocalDate = LocalDate.now()) {
        if (!Unreleased.RECAP) return
        val prefs = context.dataStore.data.first()
        val early = prefs[RecapRevealedEarlyKey] == true && BuildConfig.PREVIEW_FEATURES
        val onShow = RecapCalendar.yearOnShow(today, early) ?: return
        val inSeason = RecapCalendar.onHome(today, early)
        val told = prefs[RecapToldKey] ?: 0
        if (!inSeason || told >= onShow) return
        val database = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).database()
        val enough = runCatching { RecapReader.hasEnough(database, onShow) }.getOrDefault(false)
        val due = due(onShow, inSeason, enough, told, RecapNote.years(context)) ?: return
        if (post(context, due)) context.dataStore.edit { it[RecapToldKey] = due.recap }
    }

    /** The worker for the next reveal day's morning, kept by WorkManager through a restart of the phone. */
    fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        var at = RecapCalendar.revealDay(now.year).atTime(MORNING, 0)
        if (!at.isAfter(now)) at = RecapCalendar.revealDay(now.year + 1).atTime(MORNING, 0)
        val request = OneTimeWorkRequestBuilder<RecapAlertWorker>()
            .setInitialDelay(Duration.between(now, at).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request) }
            .onFailure { Log.w(TAG, "Could not plan the look on the reveal day", it) }
    }

    /** The second line: the notes waiting in it when some are, something short and warm otherwise. */
    fun line(context: Context, notes: Int): String =
        if (notes > 0) context.resources.getQuantityString(R.plurals.recap_alert_notes, notes, notes)
        else context.getString(R.string.recap_alert_line)

    @SuppressLint("MissingPermission")
    private fun post(context: Context, due: Due): Boolean {
        val notifier = NotificationManagerCompat.from(context)
        if (!notifier.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Made each time, so its name in Android's settings is in the app's language now (ChannelNames).
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.recap_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_RECAP, due.recap).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.recap_alert_title, due.recap.toString()))
            .setContentText(line(context, due.notes))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return runCatching { notifier.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not tell about the Recap", it) }
            .isSuccess
    }
}

/** The reveal day's look (RecapAlert.schedule): tells if it is due, then plans next year's. */
class RecapAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { RecapAlert.check(applicationContext) }
        RecapAlert.schedule(applicationContext)
        return Result.success()
    }
}
