/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dd3boh.outertune.constants.Unreleased
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Sends the plays waiting for ListenBrainz (ListenOutbox) once the phone has a connection, in
 * batches (OutboxRun), one request a second at most through the client's gate.
 *
 * Same shape as RecogniseLaterWorker: no @HiltWorker, the sender through an entry point, so
 * WorkManager's default factory builds it, and WorkManager keeps it through a restart of the phone.
 * Scheduled after a play that could not go, at the app's start while anything waits, and after a
 * new token; for a reason of the moment it runs again later, from a minute on and longer each time.
 */
class ListenBrainzWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sender(): ListenBrainzSender
    }

    override suspend fun doWork(): Result {
        if (!Unreleased.LISTENBRAINZ) return Result.success()
        val sender = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).sender()
        // Again while it all went: a play that could not go while this ran asked for a run, and
        // this one is it.
        repeat(ROUNDS) {
            when (sender.sendWaiting()) {
                OutboxRun.Next.LATER -> return Result.retry()
                OutboxRun.Next.TOKEN_REFUSED -> return Result.success()
                OutboxRun.Next.DONE -> if (!ListenOutbox.anyWaiting(applicationContext)) return Result.success()
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "listenbrainz_outbox"
        private const val ROUNDS = 3

        /** Sends what waits once there is a connection. A run already waiting or under way is enough. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ListenBrainzWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
