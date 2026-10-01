/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        // One run at a time, and a run that had to wait reads the settings only once it has the
        // folder, so it sees what the one before it recorded. That is what stops a second start of
        // the same run from writing a second copy (see shouldSkip), and it also keeps Back up now
        // and a Keep change from working on the folder while a scheduled run is half way through.
        AutoBackup.folderLock.withLock { backUp(applicationContext) }
        Result.success()
    }

    private suspend fun backUp(context: Context) {
        val prefs = context.dataStore.data.first()
        val manual = inputData.getBoolean(AutoBackup.INPUT_MANUAL, false)
        val folder = prefs[AutoBackupFolderKey] ?: ""

        // The periodic schedule is cancelled when the switch goes off, but a run already picked up
        // by WorkManager can still arrive. Back up now is the user's explicit action and runs
        // regardless of the switch, which is why it announces itself.
        if (!manual && prefs[AutoBackupEnabledKey] != true) {
            Log.i(TAG, "Automatic backups are off, skipping")
            return
        }

        // The interval of the schedule that started this run, which is the one it has to keep to.
        // A schedule from before it was passed in falls back to the setting.
        val hours = inputData.getInt(AutoBackup.INPUT_INTERVAL_HOURS, 0).takeIf { it > 0 }
            ?: prefs[AutoBackupIntervalHoursKey] ?: AutoBackup.DEFAULT_INTERVAL_HOURS
        val lastBackupAt = prefs[AutoBackupLastRunKey] ?: 0L
        if (AutoBackupPolicy.shouldSkip(
                manual = manual,
                requestedAt = inputData.getLong(AutoBackup.INPUT_REQUESTED_AT, 0L),
                lastBackupAt = lastBackupAt,
                now = System.currentTimeMillis(),
                intervalHours = hours,
            )
        ) {
            val minutes = (System.currentTimeMillis() - lastBackupAt) / 60_000
            Log.i(
                TAG,
                if (manual) "Back up now already done by an earlier attempt, skipping"
                else "Last backup was ${minutes}min ago, too soon for every ${hours}h, skipping"
            )
            return
        }

        if (folder.isBlank()) {
            record(context, context.getString(R.string.auto_backup_folder_gone))
            return
        }

        try {
            val tree = DocumentFile.fromTreeUri(context, folder.toUri())
            if (tree == null || !tree.canWrite()) {
                record(context, context.getString(R.string.auto_backup_folder_gone))
                return
            }

            // Stopped before anything was written: nothing to finish, and WorkManager runs it again.
            // Not CoroutineWorker.coroutineContext, which is a deprecated dispatcher, not this job.
            currentCoroutineContext().ensureActive()

            val appName = context.getString(R.string.app_name)
            val startedAt = System.currentTimeMillis()
            val name = AutoBackupPolicy.fileName(appName, MusicDatabase.MUSIC_DATABASE_VERSION, LocalDateTime.now())
            val database = EntryPointAccessors.fromApplication(context, Deps::class.java).database()
            Log.i(TAG, "Wrote ${write(context, tree, appName, name, database)}")

            // The file is whole from here on, and has to be on record even if this run has been
            // stopped. Stopping cancels the coroutine but cannot interrupt the write, so a stopped
            // run still finishes its file, and WorkManager then starts it again at once. On the
            // phone that happened a second after launch, as the app went to the background, and
            // the restart wrote a second backup a second after the first. Now it waits for the
            // lock and finds this one recorded. The start time, not the end, so that a Back up now
            // pressed during this write is not taken as served by it.
            withContext(NonCancellable) {
                record(context, AutoBackup.RESULT_OK, ranAt = startedAt)
            }

            // Pruned after the write, never before, so a failed write cannot cost an older backup.
            AutoBackup.prune(context, tree, appName, prefs[AutoBackupKeepKey] ?: AutoBackup.DEFAULT_KEEP)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Automatic backup failed", e)
            record(context, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Writes the backup into [tree] as [name] and returns the name it ended up with, which is not
     * always [name]: Android's provider adds " (1)" to a name that is taken.
     *
     * Written as AutoBackupPolicy.partialName and renamed once whole, so that nothing under a
     * backup's name is ever half a file. Left like that, it would count towards Keep and push a good
     * older backup out, and Restore would accept it. A write that fails deletes its file. One that
     * never gets the chance, because the process was killed mid-write (a Samsung phone does that
     * when the app is swiped out of recents with nothing playing), leaves only the temporary name,
     * which pruning removes later. A provider that cannot rename gets the final name from the
     * start, as before, and has only the delete on failure.
     *
     * A rename only counts when the name it ends up with is a backup's name of this app's, which
     * Android's " (1)" copies are. Anything else, or a provider that will not say what it renamed
     * the file to, is a failed run with nothing left behind. Recorded as a success, it would go
     * wrong either way. Under a name Keep does not count, it would never be pruned. Still under its
     * temporary name, it looks like what a killed write leaves, so the first pruning more than an
     * hour later deletes it (AutoBackupPolicy.leftoverPartials) and the backup on record is gone.
     * Restore is not the reason: its picker asks only for application/octet-stream and reads any
     * zip, whatever its name.
     */
    private fun write(context: Context, tree: DocumentFile, appName: String, name: String, database: MusicDatabase): String {
        val partial = AutoBackupPolicy.partialName(name)
        val created = tree.createFile(MIME, partial)
            ?: throw IOException("Could not create $partial in the backup folder")
        val renames = AutoBackup.canRename(context, created.uri)
        val file = if (renames) created else {
            AutoBackup.delete(context, created.uri, partial)
            tree.createFile(MIME, name) ?: throw IOException("Could not create $name in the backup folder")
        }
        // A rename can change the document's uri, and the delete below has to reach it either way.
        var current = file.uri
        try {
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                BackupWriter.write(context, database, stream)
            } ?: throw IOException("Could not open $name for writing")
            if (!renames) return file.name ?: name
            current = DocumentsContract.renameDocument(context.contentResolver, file.uri, name)
                ?: throw IOException("Could not rename $partial to $name")
            val renamedTo = DocumentFile.fromSingleUri(context, current)?.name
            if (renamedTo == null || !AutoBackupPolicy.isBackup(appName, renamedTo)) {
                throw IOException(
                    if (renamedTo == null) "Renamed $partial, but could not read its new name"
                    else "Renamed $partial to $renamedTo, which is not a backup's name"
                )
            }
            return renamedTo
        } catch (e: Exception) {
            // No file at all is the honest outcome, under whatever name it has.
            AutoBackup.delete(context, current, "the unfinished $name")
            throw e
        }
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

/**
 * The schedule, the two ways of asking for it (on every launch, and from the settings screen), and
 * the pruning that Keep asks for.
 */
object AutoBackup {
    private const val TAG = "AutoBackup"
    private const val WORK_NAME = "auto_backup"
    private const val WORK_NAME_NOW = "auto_backup_now"

    /** Input data flag: a run the user asked for, which ignores the switch. */
    const val INPUT_MANUAL = "manual"

    /** Input data: when Back up now was pressed, so a second start of that request can tell it is done. */
    const val INPUT_REQUESTED_AT = "requested_at"

    /** Input data: the interval of the schedule a run belongs to, in hours. */
    const val INPUT_INTERVAL_HOURS = "interval_hours"

    /**
     * Held by anything that writes to or deletes from the backup folder: the worker, whichever
     * way it was started, and a Keep change. WorkManager runs everything in this process.
     */
    internal val folderLock = Mutex()

    /** For the prune a Keep change starts, which should finish even if Settings is left. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the worker stores as the last result when everything went well. */
    const val RESULT_OK = "ok"

    const val DEFAULT_INTERVAL_HOURS = 168
    const val DEFAULT_KEEP = 5

    /** Every day, every week. */
    /**
     * In hours. Six hourly up to yearly, because what this is worth keeping up with varies hugely:
     * somebody curating daily wants one every few hours, and somebody whose library has not
     * changed since last spring does not want fifty two copies of it a year.
     */
    val INTERVAL_CHOICES = listOf(6, 24, 168, 720, 4380, 8760)
    /**
     * How many backups the folder may hold, as a range rather than a few fixed choices: a phone
     * with a small library and a lot of space wants a different number from one with the opposite,
     * and there is no reason to guess on their behalf. One is a real answer and so is twenty.
     */
    const val KEEP_MIN = 1
    const val KEEP_MAX = 20

    /**
     * schedule() calls, applied one at a time in the order they were made. Off the main thread,
     * where every caller is, because finding out what is scheduled is a query of WorkManager's
     * database. In order, so that a switch turned on and straight off again ends up off.
     */
    private val scheduleCalls = Channel<suspend () -> Unit>(Channel.UNLIMITED).also { calls ->
        scope.launch {
            for (call in calls) {
                try {
                    call()
                } catch (e: Exception) {
                    // Nobody to hand it to, and one failed call must not stop the ones after it.
                    Log.w(TAG, "Could not apply the automatic backup schedule", e)
                }
            }
        }
    }

    /**
     * Applies the current settings: schedules the backups if they are not scheduled, cancels them
     * if they are off or have nowhere to go, and replaces the schedule only when it was made for
     * another interval than the settings say (AutoBackupPolicy.schedulePolicy).
     *
     * Every launch calls this, and it used to replace the schedule every time. In WorkManager 2.8.1
     * replacing (ExistingPeriodicWorkPolicy.UPDATE) keeps the time of the last run but resets the
     * count of runs to zero, and a periodic run with a count of zero is due at the time of the last
     * one: in the past. So every launch started a backup within seconds, whatever the interval said.
     * KEEP leaves a schedule that exists alone, when it last ran included. A new interval still
     * has to replace it, and that still runs at once in 2.8.1; the worker's own check is what keeps
     * that from being a second backup within the interval.
     *
     * Returns at once and applies shortly after, in order with the other calls.
     *
     * The overrides exist for the same reason BackgroundCheckWorker has one: the preference setter
     * is fire and forget, so a call straight after it would read the old value and the change would
     * appear to do nothing until the next launch.
     */
    fun schedule(
        context: Context,
        enabled: Boolean? = null,
        folder: String? = null,
        hours: Int? = null,
    ) {
        val appContext = context.applicationContext
        scheduleCalls.trySend { applySchedule(appContext, enabled, folder, hours) }
    }

    private suspend fun applySchedule(context: Context, enabled: Boolean?, folder: String?, hours: Int?) {
        val prefs = context.dataStore.data.first()
        val manager = WorkManager.getInstance(context)
        val on = enabled ?: prefs[AutoBackupEnabledKey] ?: false
        val where = folder ?: prefs[AutoBackupFolderKey] ?: ""
        if (!on || where.isBlank()) {
            manager.cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Automatic backup off")
            return
        }

        val every = (hours ?: prefs[AutoBackupIntervalHoursKey] ?: DEFAULT_INTERVAL_HOURS).coerceAtLeast(1)
        // A cancelled schedule stays listed until WorkManager clears it out, and is not one:
        // enqueueing over it starts afresh whatever the policy.
        val current = manager.getWorkInfosForUniqueWork(WORK_NAME).await().firstOrNull { !it.state.isFinished }
        val policy = AutoBackupPolicy.schedulePolicy(current?.tags, every)
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(every.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(INPUT_INTERVAL_HOURS to every))
            .addTag(AutoBackupPolicy.intervalTag(every))
            .setConstraints(
                // A backup is a copy of the whole database; not worth a nearly flat battery, and
                // pointless on a nearly full disk.
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        Log.i(
            TAG,
            "Automatic backup every ${every}h, " + when {
                current == null -> "scheduled"
                policy == ExistingPeriodicWorkPolicy.KEEP -> "already scheduled"
                else -> "replacing the schedule for " +
                    (AutoBackupPolicy.taggedInterval(current.tags)?.let { "every ${it}h" } ?: "an unknown interval")
            }
        )
    }

    /**
     * Back up now: one run, no constraints, and the switch does not have to be on.
     *
     * A second press while one is still waiting or writing is dropped rather than replacing it.
     * Replacing stopped the first, which cannot stop its write, so a double tap was two files.
     */
    fun runNow(context: Context) = enqueueNow(context, ExistingWorkPolicy.KEEP)

    /**
     * A folder was just picked while automatic backups are on: one backup there now. The schedule
     * keeps its own time, so otherwise the new folder would stay empty until the next backup is
     * due, which can be a week or a year away.
     *
     * Only once the settings say [folder]. Settings saves it fire and forget, the worker reads the
     * folder from the settings when it starts, and a run that started before the save landed would
     * back up into the old one. If it has not landed within a few seconds, most likely because
     * another folder was picked straight after, this is dropped and that pick's own call stands.
     *
     * Queued after a Back up now that is already writing, rather than dropped like a second press,
     * since that one may be writing into the old folder. One that has not started yet will write
     * into the new folder, and then this run finds a backup made after it was asked for and skips
     * (AutoBackupPolicy.shouldSkip), so the folder still gets one backup, not two.
     */
    fun backUpToNewFolder(context: Context, folder: String) {
        val appContext = context.applicationContext
        scope.launch {
            val saved = withTimeoutOrNull(FOLDER_SAVE_WAIT_MS) {
                appContext.dataStore.data.first { it[AutoBackupFolderKey] == folder }
            }
            if (saved == null) {
                Log.w(TAG, "The new backup folder was not saved in time, no first backup there")
                return@launch
            }
            enqueueNow(appContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private const val FOLDER_SAVE_WAIT_MS = 10_000L

    private fun enqueueNow(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInputData(workDataOf(INPUT_MANUAL to true, INPUT_REQUESTED_AT to System.currentTimeMillis()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_NOW, policy, request)
        Log.i(TAG, "Backup requested now")
    }

    /**
     * The backups that setting Keep to [keep] would delete from the folder as it is now, by the
     * same listing and the same AutoBackupPolicy.toDelete that pruning uses, so that Settings can
     * say how many before it does. Runs off the main thread by itself.
     *
     * Empty when there is nothing to delete, which includes having no folder at all. Null when
     * there is a folder but it could not be read: the provider is offline, the card is out, the
     * grant is gone, or the folder cannot be written to, where applyKeep deletes nothing either.
     * Those two are kept apart because the next backup that does reach the folder still prunes
     * it to [keep], so a folder that could not be read is not one with nothing to lose.
     */
    suspend fun wouldDelete(context: Context, keep: Int): List<String>? = withContext(Dispatchers.IO) {
        try {
            val folder = context.dataStore.data.first()[AutoBackupFolderKey] ?: ""
            if (folder.isBlank()) return@withContext emptyList()
            val tree = DocumentFile.fromTreeUri(context, folder.toUri())?.takeIf { it.canWrite() }
                ?: throw IOException("The backup folder cannot be written to")
            val names = listChildren(context, tree.uri).map { it.first }
            AutoBackupPolicy.toDelete(context.getString(R.string.app_name), names, keep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the backup folder to count what Keep $keep would delete", e)
            null
        }
    }

    /**
     * Keep was lowered and the user agreed to lose [confirmed], what [wouldDelete] counted: deletes
     * them now instead of at the next backup, which could be a week away. Until then, lowering
     * Keep looked like it did nothing. Says how many went, since the folder is somewhere the user
     * is not looking.
     *
     * Never more than [confirmed], even if the folder has changed since it was counted (a backup
     * written in between would put one more over the line): nothing goes that the user was not
     * told about. The next backup's pruning brings the folder the rest of the way.
     *
     * Whether or not the switch is on: Back up now writes to the same folder and prunes by the same
     * number, so Keep is about the folder, not the schedule.
     */
    fun applyKeep(context: Context, keep: Int, confirmed: Collection<String>) {
        val appContext = context.applicationContext
        val allowed = confirmed.toSet()
        scope.launch {
            val removed = try {
                folderLock.withLock {
                    val tree = writableFolder(appContext)
                    if (tree == null) {
                        Log.w(TAG, "Keep changed to $keep, but the backup folder is not available")
                        return@withLock 0
                    }
                    prune(appContext, tree, appContext.getString(R.string.app_name), keep, only = allowed)
                }
            } catch (e: Exception) {
                // Its own scope has no one to hand this to, and an uncaught exception there would
                // take the app down over a settings change.
                Log.w(TAG, "Could not apply Keep $keep", e)
                0
            }
            if (removed > 0) withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    appContext.resources.getQuantityString(R.plurals.auto_backup_pruned, removed, removed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** The backup folder from the settings, when there is one and it can be written to. */
    private suspend fun writableFolder(context: Context): DocumentFile? {
        val folder = context.dataStore.data.first()[AutoBackupFolderKey] ?: ""
        if (folder.isBlank()) return null
        return DocumentFile.fromTreeUri(context, folder.toUri())?.takeIf { it.canWrite() }
    }

    /**
     * Deletes this app's backups in [tree] beyond the newest [keep] and returns how many went, and
     * clears away any of its half-written ones left by a killed write. Call it holding [folderLock],
     * off the main thread. With [only], deletes none but those (AutoBackupPolicy.confirmedToDelete).
     *
     * A file that will not go is logged with the reason and the rest still go. A folder that cannot
     * be listed is logged and nothing goes. It never throws: by the time the worker prunes, the
     * backup it wrote is safe, and a pruning problem is not a reason to report that as a failure.
     */
    internal fun prune(context: Context, tree: DocumentFile, appName: String, keep: Int, only: Set<String>? = null): Int {
        val children = try {
            listChildren(context, tree.uri)
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the backup folder to prune it", e)
            return 0
        }
        // Only names that are ours are ever considered; the folder may hold anything else.
        val names = children.map { it.first }
        val doomed = (
            if (only == null) AutoBackupPolicy.toDelete(appName, names, keep)
            else AutoBackupPolicy.confirmedToDelete(appName, names, keep, only)
        ).toSet()
        // Not backups and not counted: what a write killed half way left under its temporary name.
        val leftovers = AutoBackupPolicy.leftoverPartials(appName, names, LocalDateTime.now()).toSet()
        var removed = 0
        for ((name, uri) in children) {
            when (name) {
                in doomed -> if (delete(context, uri, name)) removed++
                in leftovers -> delete(context, uri, name)
            }
        }
        if (doomed.isNotEmpty()) Log.i(TAG, "Kept $keep, removed $removed of ${doomed.size}")
        return removed
    }

    /**
     * Whether the provider says it can rename [document]. Asked rather than tried, so that a provider
     * that cannot is never handed a whole backup it would then be left holding under the wrong name.
     */
    internal fun canRename(context: Context, document: Uri): Boolean = try {
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { c -> c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0 }
            ?: false
    } catch (e: Exception) {
        Log.w(TAG, "Could not ask whether the backup folder can rename, writing under the final name", e)
        false
    }

    /**
     * Deletes one document and says whether it went, logging why when it did not.
     *
     * Not DocumentFile.delete, which catches whatever the provider threw and returns false, so the
     * log could only say that a file had stayed, never why.
     */
    internal fun delete(context: Context, uri: Uri, name: String): Boolean = try {
        if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            Log.i(TAG, "Removed $name")
            true
        } else {
            // It only returns false for a checked exception, which it logs itself first.
            Log.w(TAG, "Could not remove $name, see the DocumentsContract warning before this")
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not remove $name", e)
        false
    }

    /**
     * Name and document uri of everything directly inside [folder], in one query.
     *
     * DocumentFile.listFiles returns an empty list when the provider refuses, which reads exactly
     * like an empty folder, and asks for each name separately afterwards. This throws instead.
     */
    private fun listChildren(context: Context, folder: Uri): List<Pair<String, Uri>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val cursor = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        ) ?: throw IOException("The provider returned nothing for $children")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    add(name to DocumentsContract.buildDocumentUriUsingTree(folder, id))
                }
            }
        }
    }
}
