package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.BackupWriter
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()

    /**
     * Whether a backup is being written, so the button can say so.
     *
     * It used to block the thread it was called on and announce itself with a toast when it was
     * over. On a large library that is several seconds of an app that appears to have ignored the
     * tap, which is the point at which people press it again.
     *
     * Every instance shares it, because Backup settings and setup each get their own ViewModel
     * and a backup or restore keeps running under NonCancellable after its screen is left.
     */
    val backupInProgress: MutableStateFlow<Boolean> = backupRunning

    /**
     * Whether a restore is running, for the same reason as [backupInProgress]: restoring a
     * real-sized library is seconds of disk I/O and Room migration work, and the button needs to
     * say so instead of looking ignored.
     *
     * Every instance shares it, because Backup settings and setup each get their own ViewModel
     * and a backup or restore keeps running under NonCancellable after its screen is left.
     */
    val restoreInProgress: MutableStateFlow<Boolean> = restoreRunning

    fun backup(uri: Uri) {
        // Also refused while a restore runs: BackupWriter.write reads database.openHelper's
        // writableDatabase, which reopens a connection to the very file restore is mid-checkpoint
        // or mid-overwrite on, producing a backup that looks successful but is not.
        if (backupInProgress.value || restoreInProgress.value) return
        backupInProgress.value = true
        viewModelScope.launch {
            // Finished, and reported, even if the screen is left while it writes. Leaving cancelled
            // this scope: the blocking write carried on to the end anyway, and the cancellation then
            // surfaced as a failure, "Couldn't create backup" for a file that had been written in
            // full. The dispatcher stays Main in here, so the toast is safe.
            withContext(NonCancellable) {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.applicationContext.contentResolver.openOutputStream(uri)?.use { stream ->
                            // The zip layout lives in BackupWriter so the scheduled backup writes the
                            // very same file.
                            BackupWriter.write(context, database, stream)
                        }
                    }
                }
                backupInProgress.value = false
                result.onSuccess {
                    Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    reportException(it)
                    Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun restore(uri: Uri) {
        // Also refused while a backup runs, for the same reason in reverse. The flags are shared
        // by every instance, so this holds across Backup settings and setup. The scheduled
        // AutoBackupWorker does not come through here, so this guard does not cover it.
        if (restoreInProgress.value || backupInProgress.value) return
        restoreInProgress.value = true
        viewModelScope.launch {
            // Same reasoning as backup(): finished and reported even if the screen is left mid
            // restore, rather than left half applied because the scope it was launched in died.
            withContext(NonCancellable) {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        restoreValidatedDatabase(uri)
                    }
                }
                restoreInProgress.value = false
                result.onSuccess { restored ->
                    if (restored) {
                        val stopIntent = Intent(context, MusicService::class.java)
                        context.stopService(stopIntent)
                        val startIntent = Intent(context, MainActivity::class.java)
                        startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(startIntent)
                        exitProcess(0)
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.err_restore_incompatible_database),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.onFailure {
                    reportException(it)
                    Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Reads [uri]'s zip, validates the embedded database in a probe file before touching
     * anything live, and only swaps the probe and the settings bytes in once that validation has
     * passed. Returns whether the swap happened.
     *
     * The live database is checkpointed and closed only after the probe is already known good,
     * not before: a refused backup does not restart the app, so closing first would leave it
     * running on a closed database. The settings bytes are kept in memory rather than written as they are read for the
     * same reason: BackupWriter always writes the settings entry before the database entry, so
     * writing them out immediately would leave a half-applied restore (new settings, old
     * database) on anything that fails validation.
     */
    private fun restoreValidatedDatabase(uri: Uri): Boolean {
        var settingsBytes: ByteArray? = null
        var databaseValidated = false
        val probeFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)

        context.applicationContext.contentResolver.openInputStream(uri)?.use {
            it.zipInputStream().use { inputStream ->
                var entry = inputStream.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        SETTINGS_FILENAME -> {
                            settingsBytes = inputStream.readBytes()
                        }

                        InternalDatabase.DB_NAME -> {
                            Log.i(TAG, "Testing new database for compatibility...")
                            probeFile.parentFile?.apply {
                                if (!exists()) mkdirs()
                            }
                            // A wal/shm left behind by an earlier attempt belongs to whatever was
                            // written to this path before, not the fresh copy below; deleting them
                            // first stops Room replaying pages from an unrelated database into it.
                            File(probeFile.path + "-wal").delete()
                            File(probeFile.path + "-shm").delete()
                            FileOutputStream(probeFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }

                            databaseValidated = try {
                                val t = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
                                try {
                                    // A database that opens and migrates but fails
                                    // PRAGMA integrity_check is refused here.
                                    t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                } finally {
                                    // In a finally, so a failed open or migration above does not
                                    // leave the probe file held open for the next attempt.
                                    t.close()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "DB validation failed", e)
                                false
                            }
                        }
                    }
                    entry = inputStream.nextEntry
                }
            }
        }

        if (!databaseValidated) {
            Log.e(TAG, "Incompatible or missing database, aborting restore")
            return false
        }

        Log.i(TAG, "Found valid database, proceeding with restore")
        database.checkpoint()
        database.close()

        // FULL does not truncate the wal file the way TRUNCATE would, and getting the live path
        // through openHelper.writableDatabase after close() would reopen a live connection to the
        // very file this is about to overwrite with a raw byte copy. Neither sidecar is read by
        // this copy, so any left behind is stale the moment it is written; delete both rather
        // than let SQLite try to replay them against a main file that changed underneath it.
        val liveDbFile = context.getDatabasePath(InternalDatabase.DB_NAME)
        File(liveDbFile.path + "-wal").delete()
        File(liveDbFile.path + "-shm").delete()

        probeFile.inputStream().use { input ->
            FileOutputStream(liveDbFile).use { outputStream ->
                input.copyTo(outputStream)
            }
        }

        settingsBytes?.let { bytes ->
            (context.filesDir / "datastore" / SETTINGS_FILENAME).outputStream().use { outputStream ->
                outputStream.write(bytes)
            }
        }

        return true
    }

    companion object {
        const val SETTINGS_FILENAME = BackupWriter.SETTINGS_FILENAME

        private val backupRunning = MutableStateFlow(false)
        private val restoreRunning = MutableStateFlow(false)
    }
}
