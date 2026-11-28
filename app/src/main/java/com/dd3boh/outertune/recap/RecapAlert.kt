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
import com.dd3boh.outertune.constants.RecapPopUpShownKey
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
import kotlinx.coroutines.guava.await
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * "Your 2026 Recap is here" (his ask of 22 Nov 2026: "recap as a notification", "with
 * highest priority"): once a year, when the year's Recap is out (RecapCalendar, the debug options'
 * early reveal included) in its season and holds enough music, as the pop-up does (RecapEntry), a
 * heads-up notification on its own channel; a tap opens the Recap. Its second line says how many
 * notes from your past self wait in it, when some do (RecapNote).
 *
 * It is for a phone where the app is not opened: whoever opens it gets the pop-up. So it comes
 * only while the pop-up has not shown that year's Recap, a Recap opened here counts as told
 * (RecapNotesOpen.opened), which also takes it out of the shade, and it never comes in the night.
 * A worker looks on the reveal day's morning and, while it is owed, a while after the app starts
 * ([AFTER_START]): a start tells nothing itself, since the pop-up opening with the app has not had
 * its turn yet. A look in the night waits for the morning. Told once a year (RecapToldKey); without
 * Android 13's permission to post, nothing, and it is looked for again at the next start.
 */
object RecapAlert {
    const val CHANNEL_ID = "recap"
    private const val NOTIFICATION_ID = 4291
    private const val WORK_NAME = "recap_alert"
    private const val TAG = "RecapAlert"

    /** On the tap: that year's Recap opens (MainActivity, RecapDoor). */
    const val EXTRA_OPEN_RECAP = "recap_open_recap"

    /** The hours of the day it may come in, from [MORNING] (the reveal day's look) until [EVENING]: never in the night. */
    private const val MORNING = 10
    private const val EVENING = 21

    /**
     * How long after a start of the app the look comes, while one is owed: an app opened by the
     * listener shows its pop-up first, once what comes up before it (the questions after an
     * update, the welcome page) is gone, and then there is nothing to tell.
     */
    private val AFTER_START: Duration = Duration.ofMinutes(30)

    /** Written on each look planned: the time it is for (policyAtStart). */
    private const val AT_TAG = "recap_look_at="

    /** What to tell: the Recap of [recap], and the notes from the year before that wait in it ([notes], 0 for none). */
    data class Due(val recap: Int, val notes: Int)

    /**
     * Whether to tell about the Recap on show ([onShow], RecapCalendar.yearOnShow), in its season
     * ([inSeason], RecapCalendar.onHome) and holding enough music ([enough]), not told about yet
     * ([toldYear], 0 for none) nor shown by its pop-up ([popUpShown], RecapPopUpShownKey): with how
     * many notes were sealed for it, those kept under the year before ([counts], RecapNote.years).
     * Null otherwise.
     */
    fun due(onShow: Int?, inSeason: Boolean, enough: Boolean, toldYear: Int, popUpShown: Int, counts: Map<Int, Int>): Due? {
        val recap = onShow ?: return null
        if (!inSeason || !enough || toldYear >= recap || popUpShown >= recap) return null
        return Due(recap, counts[recap - 1] ?: 0)
    }

    /** Whether [at] is a decent hour to come in: the day, never the night. */
    fun daytime(at: LocalDateTime): Boolean = at.hour in MORNING until EVENING

    /** The first moment from [from] on that is [daytime]: [from] itself, or the next morning. */
    fun nextDaytime(from: LocalDateTime): LocalDateTime {
        val morning = from.toLocalDate().atTime(MORNING, 0)
        return when {
            from.isBefore(morning) -> morning
            daytime(from) -> from
            else -> morning.plusDays(1)
        }
    }

    /** The next reveal day's morning after [now]. */
    fun revealMorning(now: LocalDateTime): LocalDateTime {
        val at = RecapCalendar.revealDay(now.year).atTime(MORNING, 0)
        return if (at.isAfter(now)) at else RecapCalendar.revealDay(now.year + 1).atTime(MORNING, 0)
    }

    /** The look a start of the app at [now] plans: [AFTER_START] later in the day while one is [owed], the next reveal day's morning otherwise. */
    fun lookAfterStart(now: LocalDateTime, owed: Boolean): LocalDateTime =
        if (owed) nextDaytime(now.plus(AFTER_START)) else revealMorning(now)

    /**
     * How a start plans the look it [wanted] over the one [planned] already (null for none, or one
     * planned before its time was written on it): kept when that one comes as soon, so that a start
     * never puts a look off, not even the start the look itself makes when it runs; planned anew
     * otherwise (next year's, after a look that found nothing to tell then).
     */
    fun policyAtStart(planned: LocalDateTime?, wanted: LocalDateTime): ExistingWorkPolicy =
        if (planned != null && !planned.isAfter(wanted)) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE

    /** The tag a look planned for [at] carries. */
    fun tagFor(at: LocalDateTime): String = AT_TAG + at

    /** The time a look is planned for, from its [tags] (WorkInfo.tags), or null. */
    fun plannedAt(tags: Set<String>?): LocalDateTime? =
        tags?.firstOrNull { it.startsWith(AT_TAG) }?.let { runCatching { LocalDateTime.parse(it.removePrefix(AT_TAG)) }.getOrNull() }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): MusicDatabase
    }

    /**
     * What is owed on [today], read from the phone: null for nothing, and the log says why ([who]
     * looked: the start or the look). Off the main thread.
     */
    private suspend fun find(context: Context, today: LocalDate, who: String): Due? {
        fun none(why: String): Due? = null.also { Log.i(TAG, "$who on $today: nothing to tell, $why") }
        if (!Unreleased.RECAP) return null
        val prefs = context.dataStore.data.first()
        val early = prefs[RecapRevealedEarlyKey] == true && BuildConfig.PREVIEW_FEATURES
        val onShow = RecapCalendar.yearOnShow(today, early) ?: return none("no Recap is out")
        val inSeason = RecapCalendar.onHome(today, early)
        val told = prefs[RecapToldKey] ?: 0
        val shown = prefs[RecapPopUpShownKey] ?: 0
        if (!inSeason || told >= onShow || shown >= onShow) return none("the $onShow Recap: in its season $inSeason, told $told, its pop-up shown $shown")
        // Nothing it could post: no look planned for it; the next start looks again.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return none("notifications are off")
        val database = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).database()
        val enough = runCatching { RecapReader.hasEnough(database, onShow) }
            .onFailure { Log.w(TAG, "Could not count the music of $onShow", it) }
            .getOrDefault(false)
        return due(onShow, inSeason, enough, told, shown, RecapNote.years(context)) ?: none("too little music in $onShow")
    }

    /**
     * As the app starts: tells nothing, plans the look ([lookAfterStart]), without ever putting
     * off one planned already ([policyAtStart]). Off the main thread.
     */
    suspend fun plan(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val owed = runCatching { find(context, now.toLocalDate(), "Start") != null }
            .onFailure { Log.w(TAG, "The start's look at $now failed", it) }
            .getOrDefault(false)
        val wanted = lookAfterStart(now, owed)
        val planned = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).await().firstOrNull { !it.state.isFinished }
        }.getOrNull()
        enqueue(context, wanted, now, policyAtStart(plannedAt(planned?.tags), wanted))
    }

    /**
     * The worker's look: told now if it is owed, unless it is the night (a phone started at three
     * in the morning, a look overdue after the phone was off), when it waits for the morning; then
     * the next reveal day's morning is planned. Off the main thread.
     */
    suspend fun look(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val due = runCatching { find(context, now.toLocalDate(), "Look") }
            .onFailure { Log.w(TAG, "The look at $now failed", it) }
            .getOrNull()
        if (due != null && !daytime(now)) return enqueue(context, nextDaytime(now), now, ExistingWorkPolicy.REPLACE)
        if (due != null) runCatching { if (post(context, due)) context.dataStore.edit { it[RecapToldKey] = due.recap } }
            .onFailure { Log.w(TAG, "Could not tell about the Recap", it) }
        enqueue(context, revealMorning(now), now, ExistingWorkPolicy.REPLACE)
    }

    /** The worker for [at], kept by WorkManager through a restart of the phone, the time written on it. */
    private fun enqueue(context: Context, at: LocalDateTime, now: LocalDateTime, policy: ExistingWorkPolicy) {
        val minute = at.truncatedTo(ChronoUnit.MINUTES)
        val request = OneTimeWorkRequestBuilder<RecapAlertWorker>()
            .setInitialDelay(Duration.between(now, minute).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(tagFor(minute))
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request) }
            .onSuccess { Log.i(TAG, "The next look: $minute ($policy)") }
            .onFailure { Log.w(TAG, "Could not plan the look for $minute", it) }
    }

    /** "Your Recap is here" out of the shade: the Recap it tells of is open on this phone. */
    fun withdraw(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
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
            .onSuccess { Log.i(TAG, "Told about the ${due.recap} Recap, ${due.notes} notes waiting in it") }
            .onFailure { Log.w(TAG, "Could not tell about the Recap", it) }
            .isSuccess
    }
}

/** The look (RecapAlert.look): tells if it is due and the day has come, then plans the next one. */
class RecapAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { RecapAlert.look(applicationContext) }.onFailure { Log.w("RecapAlert", "The look failed", it) }
        return Result.success()
    }
}
