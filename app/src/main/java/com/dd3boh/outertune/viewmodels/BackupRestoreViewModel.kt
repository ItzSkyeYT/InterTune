package com.dd3boh.outertune.viewmodels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.DatabaseFiles
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.BackupParts
import com.dd3boh.outertune.utils.BackupWriter
import com.dd3boh.outertune.utils.PlaylistCovers
import com.dd3boh.outertune.utils.SettingsFile
import com.dd3boh.outertune.utils.SignIns
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.system.exitProcess
import com.dd3boh.outertune.utils.Toaster

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

    fun backup(uri: Uri, parts: BackupParts = BackupParts()) {
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
                            BackupWriter.write(context, database, stream, parts)
                        }
                    }
                }
                backupInProgress.value = false
                result.onSuccess {
                    Toaster.show(context, R.string.backup_create_success, Toast.LENGTH_SHORT)
                }.onFailure {
                    reportException(it)
                    Toaster.show(context, R.string.backup_create_failed, Toast.LENGTH_SHORT)
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
                        // In a task of its own, cleared first. MainActivity is singleTask, so a
                        // plain start was handed to the instance already on top and made nothing
                        // new: when the process then ended the app was gone, and somebody who had
                        // just restored a backup was looking at their launcher (a Pixel 5 on
                        // Android 14). A cleared task gets a new activity, which the system
                        // brings up in a new process once this one has ended.
                        context.startActivity(
                            Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
                        )
                        exitProcess(0)
                    } else {
                        Toaster.show(
                            context,
                            context.getString(R.string.err_restore_incompatible_database),
                            Toast.LENGTH_SHORT
                        )
                    }
                }.onFailure {
                    reportException(it)
                    Toaster.show(context, it.message, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /**
     * Reads [uri]'s zip, validates the embedded database in a probe file before touching
     * anything live, and only swaps the probe and the settings bytes in once that validation has
     * passed. Returns whether the app has to restart: after a swap, and after a swap that failed
     * once the database had been closed for it, since the app cannot go on without one.
     *
     * The live database is checkpointed and closed only after the probe is already known good,
     * not before: a refused backup does not restart the app, so closing first would leave it
     * running on a closed database. The settings bytes are kept in memory rather than written as they are read for the
     * same reason: BackupWriter always writes the settings entry before the database entry, so
     * writing them out immediately would leave a half-applied restore (new settings, old
     * database) on anything that fails validation.
     */
    private suspend fun restoreValidatedDatabase(uri: Uri): Boolean {
        var settingsBytes: ByteArray? = null
        var databaseValidated = false
        var databaseFound = false
        val probeFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
        // The photos a backup holds as playlist covers wait here, like the settings, until the
        // database is known good: a refused backup changes nothing.
        val stagedCovers = File(context.cacheDir, "playlist-covers-restore")
        stagedCovers.deleteRecursively()

        context.applicationContext.contentResolver.openInputStream(uri)?.use {
            it.zipInputStream().use { inputStream ->
                var entry = inputStream.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        SETTINGS_FILENAME -> {
                            settingsBytes = inputStream.readBytes()
                        }

                        InternalDatabase.DB_NAME -> {
                            databaseFound = true
                            Log.i(TAG, "Testing new database for compatibility...")
                            probeFile.parentFile?.apply {
                                if (!exists()) mkdirs()
                            }
                            // A wal/shm left behind by an earlier attempt belongs to whatever was
                            // written to this path before, not the fresh copy below; deleting them
                            // first stops Room replaying pages from an unrelated database into it.
                            DatabaseFiles.SIDECARS.forEach { DatabaseFiles.sidecar(probeFile, it).delete() }
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

                        else -> PlaylistCovers.stage(stagedCovers, entry.name, inputStream)
                    }
                    entry = inputStream.nextEntry
                }
            }
        }

        // A backup of the settings alone (the backup sheet lets the library be left out): there is
        // no database to check or swap, and photos only come with one.
        if (!databaseFound && settingsBytes != null) {
            stagedCovers.deleteRecursively()
            writeSettings(settingsBytes!!)
            return true
        }

        if (!databaseValidated) {
            Log.e(TAG, "Incompatible or missing database, aborting restore")
            stagedCovers.deleteRecursively()
            return false
        }

        Log.i(TAG, "Found valid database, proceeding with restore")
        // What the migration wrote into the probe goes into its own file, so that the one file
        // moved into place below holds all of it.
        if (!BackupWriter.foldLog(probeFile, check = false)) {
            stagedCovers.deleteRecursively()
            throw IOException("The backup's database could not be made whole on its own")
        }

        // The music stops before the database closes, so that playing writes nothing more into
        // it. The player's service stays up until the restart below: the open screen holds it
        // bound, so stopping it here would not end it. What it still writes after the close fails
        // on the closed database and is logged.
        withContext(Dispatchers.Main) {
            runCatching { MusicService.alive()?.stopForRestore() }
                .onFailure { Log.w(TAG, "Could not stop the music before the restore", it) }
        }

        // From the close on the app has to restart whatever happens: it cannot go on without its
        // database, and the next start opens the library in place, the backup's, or the one that
        // was there if the move did not happen. Under the backups' lock, so that no backup copies
        // the files while they change.
        try {
            synchronized(DatabaseFiles.lock) {
                // The old library's log folded into its file first, so that a process that dies
                // before the move leaves it whole. Only a help: it is replaced either way.
                runCatching { database.checkpoint() }
                database.close()
                // In one step, in place of copying over song.db, which emptied it first: a process
                // that died during the copy left a cut-off file, and SQLite's open helper deletes
                // that as damaged, so the library came back empty.
                DatabaseFiles.replace(probeFile, context.getDatabasePath(InternalDatabase.DB_NAME))
            }
        } catch (e: Exception) {
            Log.e(TAG, "The backup's database could not be put in place, the library stays as it was", e)
            reportException(e)
            stagedCovers.deleteRecursively()
            return true
        }

        // The automatic backup schedule is left as it is. The launch after the restart compares it
        // with these settings and replaces it if the interval differs (AutoBackup.schedule).
        // Settings that cannot be written leave the ones there were, beside the restored library.
        settingsBytes?.let { bytes ->
            runCatching { writeSettings(bytes) }.onFailure { Log.e(TAG, "The backup's settings could not be written", it) }
        }

        // Last, and apart from the rest: a photo that cannot be put in place costs its playlist a
        // cover, which is drawn as the first song's, and never the restore.
        runCatching { PlaylistCovers.place(stagedCovers, context.filesDir) }

        return true
    }

    /**
     * Puts a backup's settings in place, with the sign-ins already on this phone kept when the
     * backup has none: a backup leaves them out unless asked, and a restore must not sign anybody
     * out. Settings that cannot be read that way are written as they came, as before.
     */
    private fun writeSettings(bytes: ByteArray) {
        val settings = runCatching {
            runBlocking {
                val restored = SettingsFile.decode(bytes, context.cacheDir)
                SettingsFile.encode(SignIns.keptOver(restored, context.dataStore.current), context.cacheDir)
            }
        }.getOrElse {
            Log.w(TAG, "The backup's settings could not be read; written as they are", it)
            bytes
        }
        (context.filesDir / "datastore" / SETTINGS_FILENAME).outputStream().use { outputStream ->
            outputStream.write(settings)
        }
    }

    companion object {
        const val SETTINGS_FILENAME = BackupWriter.SETTINGS_FILENAME

        private val backupRunning = MutableStateFlow(false)
        private val restoreRunning = MutableStateFlow(false)
    }
}
