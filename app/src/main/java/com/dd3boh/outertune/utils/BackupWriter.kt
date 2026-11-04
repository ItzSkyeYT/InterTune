/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.dd3boh.outertune.db.DatabaseFiles
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.zipOutputStream
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * The one place that knows what a backup file is made of.
 *
 * The manual Backup entry and the scheduled worker have to produce byte for byte the same kind of
 * file, because there is a single Restore path and it must accept either without knowing which it
 * was given. Keeping the layout here, rather than in the ViewModel where it started, is what makes
 * that a fact rather than a hope.
 */
object BackupWriter {
    private const val TAG = "BackupWriter"
    const val SETTINGS_FILENAME = "settings.preferences_pb"

    /** Under the cache: the library's copy while it is checked and zipped. */
    private const val COPY_FOLDER = "backup-copy"

    /** Copies made before giving up, when SQLite's check refuses one. */
    private const val ATTEMPTS = 3

    /**
     * Writes what [parts] asks for into [output] as one zip: the settings, the database, and after it
     * the photos chosen as playlist covers, when there are any.
     *
     * Call it off the main thread: the copy waits for the database's write lock, and the database
     * can be large. The stream is wrapped and closed here, so the caller only needs to open it.
     */
    fun write(context: Context, database: MusicDatabase, output: OutputStream, parts: BackupParts = BackupParts()) {
        // The library as it stood at one moment, checked, then zipped at the compression's own
        // pace while the app goes on writing. Taken before anything goes into the zip: a copy that
        // fails then leaves no file holding the settings alone, which Restore would take for a
        // backup of the settings.
        val copy = if (parts.library) consistentCopy(context, database) else null
        try {
            output.buffered().zipOutputStream().use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                if (parts.settings) {
                    // The settings as the app holds them, written through DataStore itself, and without
                    // the account sign-ins unless they were asked for: anyone with the file could use them.
                    val prefs = context.dataStore.current.let { if (parts.signIns) it else SignIns.without(it) }
                    val bytes = runBlocking { SettingsFile.encode(prefs, context.cacheDir) }
                    zip.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                    zip.write(bytes)
                }
                if (copy != null) {
                    FileInputStream(copy).use { input ->
                        zip.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                        input.copyTo(zip)
                    }
                    // A playlist's photo cover is a file of the app's own and the database only names it,
                    // so without the file a restored playlist would show the first song's cover again.
                    // After the database, and nothing at all for a library that has none.
                    if (parts.photos) PlaylistCovers.addTo(zip, context.filesDir)
                }
            }
        } finally {
            copy?.parentFile?.deleteRecursively()
        }
    }

    /**
     * The library as it stood at one moment, in a file of its own under the cache, whole and
     * checked. The zip used to read song.db itself, seconds of compression on a big library, while
     * the app went on writing and SQLite moved new pages into the file as it was read, so a backup
     * could hold pages from before and after the same change (4 Nov 2026).
     *
     * Writers wait only while song.db and its log are copied, under a Room transaction
     * (DatabaseFiles.copy); the check and the compression come after. The check is the restore's
     * own, so a backup is only written if a restore would take it. A library SQLite itself finds
     * damaged now fails its backup here, where it used to write one that Restore refused.
     */
    private fun consistentCopy(context: Context, database: MusicDatabase): File {
        val live = context.getDatabasePath(InternalDatabase.DB_NAME)
        val folder = File(context.cacheDir, COPY_FOLDER)
        val copy = File(folder, InternalDatabase.DB_NAME)
        synchronized(DatabaseFiles.lock) {
            // Folds the log into song.db first, only so there is less of it to copy.
            database.checkpoint()
            repeat(ATTEMPTS) { attempt ->
                folder.deleteRecursively()
                DatabaseFiles.copy(live, copy, holdingWriteLock = { copyFiles -> database.transactionNow { copyFiles() } })
                if (foldLog(copy, check = true)) return copy
                Log.w(TAG, "The library's copy did not pass SQLite's check, attempt ${attempt + 1} of $ATTEMPTS")
            }
            folder.deleteRecursively()
        }
        throw IOException("The library could not be copied whole: SQLite's check refused the copy")
    }

    /**
     * Opens [file] by itself and folds its write-ahead log in, so that the one file holds all of it.
     * With [check], also SQLite's integrity check, the one a restore makes, and the library's
     * schema version. False when it does not open, is not whole, or fails the check.
     */
    internal fun foldLog(file: File, check: Boolean): Boolean {
        val folded = try {
            // Not the default error handler, which deletes a file it finds damaged and opens an
            // empty database in its place: an empty database passes the check.
            SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ) { Log.w(TAG, "SQLite found ${file.name} damaged") }.use { db ->
                val logIn = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() && it.getInt(0) == 0 }
                logIn && (!check || (db.version == MusicDatabase.MUSIC_DATABASE_VERSION && db.isDatabaseIntegrityOk))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${file.name} on its own", e)
            false
        }
        return folded && DatabaseFiles.isWhole(file)
    }
}
