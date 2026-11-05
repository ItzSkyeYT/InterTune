/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.search.SystemSearch
import com.dd3boh.outertune.utils.ErrorText
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Names the listens kept with no connection ([RecogniseLater]) once there is one, and says so in
 * one quiet notification.
 *
 * Same shape as BackgroundCheckWorker: no @HiltWorker, dependencies through an entry point, so
 * WorkManager's default factory builds it. The engine starts it as it keeps a listen, to run once
 * the phone has a connection, and WorkManager keeps it through a restart of the phone.
 *
 * One request at a time, [PACE_MS] apart: Shazam refuses after about a dozen in quick succession,
 * and an evening of Keep listening with no connection leaves dozens. Each listen goes into the list
 * of everything heard, and off the list of those waiting, as soon as its answer is in, so a run
 * stopped half way loses nothing. No answer for a reason of the moment, no connection after all or
 * Shazam refusing, ends the run, and WorkManager runs it again later.
 */
class RecogniseLaterWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun later(): RecogniseLater
        fun shazam(): ShazamClient
        fun history(): RecognitionHistory
    }

    override suspend fun doWork(): Result {
        if (!Unreleased.RECOGNISE_OFFLINE) return Result.success()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val later = deps.later()
        val named = mutableListOf<Heard>()
        var unknown = 0
        var unanswered = false
        var askedAt = 0L

        val due = later.due()
        Log.i(TAG, "${due.size} kept for later to ask about")
        for (listen in due) {
            val outcome = RecogniseLater.lookUp(listen) { print ->
                if (askedAt > 0) delay(askedAt + PACE_MS - SystemClock.elapsedRealtime())
                askedAt = SystemClock.elapsedRealtime()
                deps.shazam().ask(print)
            }
            val verdict = RecogniseLater.verdict(outcome, listen.tries)
            val said = when (verdict) {
                is RecogniseLater.Settled.Named -> "named '${verdict.track.title}'" +
                        if (verdict.track.heardAtRate != 1.0) " at ${verdict.track.heardAtRate}x" else ""
                else -> verdict.toString()
            }
            Log.i(TAG, "Kept ${(System.currentTimeMillis() - listen.heardAt) / 60_000} min ago: $said")
            if (verdict is RecogniseLater.Settled.Named) {
                // Into the list of everything heard before off this one. A run stopped in between
                // asks again, and the list takes the same song once.
                val heard = heard(verdict.track, listen.heardAt)
                deps.history().add(heard)
                // Keep listening with no connection keeps a song more than once: it is one song.
                named.removeAll { it.title == heard.title && it.artist == heard.artist }
                named += heard
            }
            later.settle(listen.heardAt, verdict)
            when (verdict) {
                is RecogniseLater.Settled.Named -> tell(named, unknown)
                RecogniseLater.Settled.Unknown -> unknown++
                RecogniseLater.Settled.Unanswered -> {
                    unanswered = true
                    break
                }
                else -> Unit
            }
        }
        tell(named, unknown)
        // What this run looked at and is still waiting, unanswered or counted, is asked again
        // later. A listen kept while it ran has a run of its own behind this one (schedule).
        val asked = due.map { it.heardAt }.toSet()
        return if (unanswered || later.due().any { it.heardAt in asked }) Result.retry() else Result.success()
    }

    /**
     * [track] as the list of everything heard keeps it, at [heardAt], with the video YouTube's
     * search gives first when it is the song ([Heard.idFor]), as the engine keeps it. Without one
     * a tap on the entry searches for the song by its name.
     */
    private suspend fun heard(track: Recognised, heardAt: Long): Heard {
        val found = YouTube.search(track.searchQuery, YouTube.SearchFilter.FILTER_SONG)
        found.exceptionOrNull()?.let { Log.w(TAG, "Search for '${track.searchQuery}' failed", ErrorText.forLog(it)) }
        val first = found.getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()
        return Heard(
            title = track.title,
            artist = track.artist.orEmpty(),
            videoId = Heard.idFor(track, first),
            at = heardAt,
            checked = true,
        )
    }

    /**
     * The songs named so far in this run, or that nothing was found: one notification, quiet, on
     * a channel of its own. A tap opens What you have heard, where each song sits at the time it
     * was heard. Without the permission to post, nothing: the list says it all the same.
     */
    @SuppressLint("MissingPermission")
    private fun tell(named: List<Heard>, unknown: Int) {
        if (named.isEmpty() && unknown == 0) return
        val context = applicationContext
        val notifier = NotificationManagerCompat.from(context)
        if (!notifier.areNotificationsEnabled()) {
            Log.i(TAG, "Notifications are off, the songs are in What you have heard")
            return
        }
        // Channels arrived in API 26 and this app still runs on 24. NotificationCompat posts fine
        // without one there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.recognise_later_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(SystemSearch.HEARD_LINK), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val songs = named.map { listOf(it.title, it.artist).filter { part -> part.isNotBlank() }.joinToString(" · ") }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // The sound bars, as everywhere song recognition is.
            .setSmallIcon(R.drawable.ic_recognise)
            .setContentTitle(
                if (named.isEmpty()) context.getString(R.string.recognise_later_none)
                else context.resources.getQuantityString(R.plurals.recognise_later_named, named.size, named.size)
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (songs.isNotEmpty()) {
            builder.setContentText(songs.joinToString(", "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(songs.joinToString("\n")))
        }
        runCatching { notifier.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "Could not post the songs named later", it) }
    }

    companion object {
        private const val TAG = "RecogniseLater"
        private const val WORK_NAME = "recognise_later"
        private const val CHANNEL_ID = "song_recognition_later"
        private const val NOTIFICATION_ID = 4246

        /** Between two requests to Shazam: Listen once asks about one window every twelve seconds and is never refused. */
        const val PACE_MS = 6_000L

        /**
         * Asks about everything kept once the phone has a connection. Each call adds a run behind
         * the one waiting, so a listen kept while a run is under way is never left for a run that
         * has already looked.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecogniseLaterWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
