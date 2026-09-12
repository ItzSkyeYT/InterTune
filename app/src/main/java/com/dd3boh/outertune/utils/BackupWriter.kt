/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipOutputStream
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
     * Writes the settings file and the database into [output] as one zip.
     *
     * Call it off the main thread: the checkpoint is a Room query, and the database copy can be
     * large. The stream is wrapped and closed here, so the caller only needs to open it.
     */
    fun write(context: Context, database: MusicDatabase, output: OutputStream) {
        output.buffered().zipOutputStream().use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            (context.filesDir / "datastore" / SETTINGS_FILENAME).inputStream().buffered().use { input ->
                zip.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                input.copyTo(zip)
            }
            // Folds the write-ahead log into the main file first, otherwise the copy below would
            // miss everything since the last checkpoint.
            database.checkpoint()
            FileInputStream(database.openHelper.writableDatabase.path).use { input ->
                zip.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                input.copyTo(zip)
            }
        }
    }
}
