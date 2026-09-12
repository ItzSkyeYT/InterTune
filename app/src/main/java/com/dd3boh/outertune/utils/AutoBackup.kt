/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoBackupEnabledKey
import com.dd3boh.outertune.constants.AutoBackupFolderKey
import com.dd3boh.outertune.constants.AutoBackupIntervalHoursKey
import com.dd3boh.outertune.constants.AutoBackupKeepKey
import com.dd3boh.outertune.constants.AutoBackupLastResultKey
import com.dd3boh.outertune.constants.AutoBackupLastRunKey
import com.dd3boh.outertune.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Writes a backup into the folder the user chose, then removes the oldest ones beyond the count
 * they asked to keep.
 *
 * Same shape as BackgroundCheckWorker: no @HiltWorker, dependencies come through an entry point,
 * so WorkManager's default factory can build it without any configuration in the Application.
 *
 * Every outcome is Result.success. A folder that has gone away, or a provider that refuses the
 * write, is not something a retry an hour later would change, and WorkManager's backoff would
 * otherwise keep waking the device to fail again. What happened is written to the datastore
 * instead, where the settings screen shows it.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): MusicDatabase
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.dataStore.data.first()
        val manual = inputData.getBoolean(AutoBackup.INPUT_MANUAL, false)
        val folder = prefs[AutoBackupFolderKey] ?: ""

        // The periodic schedule is cancelled when the switch goes off, but a run already picked up
        // by WorkManager can still arrive. Back up now is the user's explicit action and runs
        // regardless of the switch, which is why it announces itself.
        if (!manual && prefs[AutoBackupEnabledKey] != true) {
            Log.i(TAG, "Automatic backups are off, skipping")
            return@withContext Result.success()
        }
        if (folder.isBlank()) {
            record(context, context.getString(R.string.auto_backup_folder_gone))
            return@withContext Result.success()
        }

        try {
            val tree = DocumentFile.fromTreeUri(context, folder.toUri())
            if (tree == null || !tree.canWrite()) {
                record(context, context.getString(R.string.auto_backup_folder_gone))
                return@withContext Result.success()
            }

            val appName = context.getString(R.string.app_name)
            val name = AutoBackupPolicy.fileName(appName, MusicDatabase.MUSIC_DATABASE_VERSION, LocalDateTime.now())
            val file = tree.createFile(MIME, name)
                ?: throw IOException("Could not create $name in the backup folder")
            val database = EntryPointAccessors.fromApplication(context, Deps::class.java).database()
            try {
                context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                    BackupWriter.write(context, database, stream)
                } ?: throw IOException("Could not open $name for writing")
            } catch (e: Exception) {
                // The document already exists by now. Left behind, half written but under a valid
                // name, it would count towards Keep on the next run and push a good older backup
                // out, and Restore would accept it. No file at all is the honest outcome.
                if (!file.delete()) Log.w(TAG, "Could not remove the partial $name")
                throw e
            }
            Log.i(TAG, "Wrote $name")

            // Pruned after the write, never before, so a failed write cannot cost an older backup.
            // Only names that are ours are ever considered; the folder may hold anything else.
            val children = tree.listFiles()
            val doomed = AutoBackupPolicy.toDelete(
                appName,
                children.mapNotNull { it.name },
                prefs[AutoBackupKeepKey] ?: AutoBackup.DEFAULT_KEEP,
            ).toSet()
            children.filter { it.name in doomed }.forEach { old ->
                if (old.delete()) Log.i(TAG, "Removed ${old.name}")
                else Log.w(TAG, "Could not remove ${old.name}")
            }

            record(context, AutoBackup.RESULT_OK, ranAt = System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Automatic backup failed", e)
            record(context, e.message ?: e.javaClass.simpleName)
        }
        Result.success()
    }

    private suspend fun record(context: Context, result: String, ranAt: Long? = null) {
        context.dataStore.edit { prefs ->
            prefs[AutoBackupLastResultKey] = result
            if (ranAt != null) prefs[AutoBackupLastRunKey] = ranAt
        }
    }

    companion object {
        private const val TAG = "AutoBackup"
        private const val MIME = "application/octet-stream"
    }
}

/** The schedule, and the two ways of asking for it: on every launch, and from the settings screen. */
object AutoBackup {
    private const val TAG = "AutoBackup"
    private const val WORK_NAME = "auto_backup"
    private const val WORK_NAME_NOW = "auto_backup_now"

    /** Input data flag: a run the user asked for, which ignores the switch. */
    const val INPUT_MANUAL = "manual"

    /** What the worker stores as the last result when everything went well. */
    const val RESULT_OK = "ok"

    const val DEFAULT_INTERVAL_HOURS = 168
    const val DEFAULT_KEEP = 5

    /** Every day, every week. */
    val INTERVAL_CHOICES = listOf(24, 168)
    val KEEP_CHOICES = listOf(3, 5, 10)

    /**
     * Applies the current settings, replacing whatever was scheduled before.
     *
     * Safe to call on every launch and on every change: the work is keyed by name and replaced
     * rather than stacked. The overrides exist for the same reason BackgroundCheckWorker has one:
     * the preference setter is fire and forget, so a call straight after it would read the old
     * value and the change would appear to do nothing until the next launch.
     */
    fun schedule(
        context: Context,
        enabled: Boolean? = null,
        folder: String? = null,
        hours: Int? = null,
    ) {
        val on = enabled ?: context.dataStore.get(AutoBackupEnabledKey, false)
        val where = folder ?: context.dataStore.get(AutoBackupFolderKey, "")
        if (!on || where.isBlank()) {
            cancel(context)
            return
        }

        val every = (hours ?: context.dataStore.get(AutoBackupIntervalHoursKey, DEFAULT_INTERVAL_HOURS))
            .coerceAtLeast(1)
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(every.toLong(), TimeUnit.HOURS)
            .setConstraints(
                // A backup is a copy of the whole database; not worth a nearly flat battery, and
                // pointless on a nearly full disk.
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.i(TAG, "Automatic backup every ${every}h")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "Automatic backup off")
    }

    /** Back up now: one run, no constraints, and the switch does not have to be on. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInputData(workDataOf(INPUT_MANUAL to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.i(TAG, "Backup requested now")
    }
}
