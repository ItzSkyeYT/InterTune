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
import com.dd3boh.outertune.mix.edit.JsonFileBeatFixStore
import com.dd3boh.outertune.mix.edit.JsonFileTransitionStore
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
    const val SETTINGS_FILENAME = "settings.preferences_pb"

    /**
     * The listener's own files that travel beside the settings and the database, each under its
     * path in the app's files, which is also its name in the zip: Mix's saved transitions and beat
     * fixes, kept in files and not in the database (DESIGN.md 5 has them travel with backups). A
     * backup made before they were carried restores without touching the ones on the phone.
     */
    val FILES: List<String> = listOf(JsonFileTransitionStore.FILE, JsonFileBeatFixStore.FILE)

    /**
     * Writes the settings file and the database into [output] as one zip.
     *
     * Call it off the main thread: the checkpoint is a Room query, and the database copy can be
     * large. The stream is wrapped and closed here, so the caller only needs to open it.
     */
    fun write(context: Context, database: MusicDatabase, output: OutputStream) =
        write(context.filesDir, output) {
            // Folds the write-ahead log into the main file first, otherwise the copy below would
            // miss everything since the last checkpoint.
            database.checkpoint()
            database.openHelper.writableDatabase.path
        }

    /** The same from the app's files in [filesDir]; [database] readies the database and gives the file to copy, when its turn comes. */
    internal fun write(filesDir: File, output: OutputStream, database: () -> String?) {
        output.buffered().zipOutputStream().use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            (filesDir / "datastore" / SETTINGS_FILENAME).inputStream().buffered().use { input ->
                zip.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                input.copyTo(zip)
            }
            for (name in FILES) {
                val file = filesDir / name
                if (!file.isFile) continue
                file.inputStream().buffered().use { input ->
                    zip.putNextEntry(ZipEntry(name))
                    input.copyTo(zip)
                }
            }
            FileInputStream(database()).use { input ->
                zip.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                input.copyTo(zip)
            }
        }
    }

    /** Puts [files] a backup carried back under [filesDir], by their names in the zip ([FILES]), each whole or not at all. */
    fun restoreFiles(filesDir: File, files: Map<String, ByteArray>) {
        for ((name, bytes) in files) {
            require(name in FILES) { "$name is not a file a backup carries" }
            val file = filesDir / name
            file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot make ${it.path}") }
            val next = File(file.path + ".restored")
            next.writeBytes(bytes)
            if (!next.renameTo(file)) {
                next.delete()
                throw IOException("cannot put back $name")
            }
        }
    }
}
