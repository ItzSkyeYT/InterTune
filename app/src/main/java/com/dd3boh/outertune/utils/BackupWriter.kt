/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.zipOutputStream
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
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
    const val SETTINGS_FILENAME = "settings.preferences_pb"

    /**
     * Writes what [parts] asks for into [output] as one zip: the settings, the database, and after it
     * the photos chosen as playlist covers, when there are any.
     *
     * Call it off the main thread: the checkpoint is a Room query, and the database copy can be
     * large. The stream is wrapped and closed here, so the caller only needs to open it.
     */
    fun write(context: Context, database: MusicDatabase, output: OutputStream, parts: BackupParts = BackupParts()) {
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
            if (parts.library) {
                // Folds the write-ahead log into the main file first, otherwise the copy below would
                // miss everything since the last checkpoint.
                database.checkpoint()
                FileInputStream(database.openHelper.writableDatabase.path).use { input ->
                    zip.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                    input.copyTo(zip)
                }
                // A playlist's photo cover is a file of the app's own and the database only names it,
                // so without the file a restored playlist would show the first song's cover again.
                // After the database, and nothing at all for a library that has none.
                if (parts.photos) PlaylistCovers.addTo(zip, context.filesDir)
            }
        }
    }
}
