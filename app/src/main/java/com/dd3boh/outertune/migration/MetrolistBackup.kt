/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * A Metrolist backup file, opened for MetrolistImport. The zip is read as a stream: song.db is written to a folder
 * of ours, the new app's settings.json is kept as text, anything else is skipped. Sizes are capped as Metrolist caps
 * its own (BackupArchiveLimits): a file that claims more is refused rather than filling the phone.
 */
object MetrolistBackup {
    const val DATABASE_BYTES = 512L * 1024 * 1024
    const val TEXT_BYTES = 10L * 1024 * 1024

    class Unpacked(val database: File, val settingsJson: String?)

    /** [input]'s song.db, written to [folder], with the new app's settings.json if it has one; null without a database. */
    fun unpack(input: InputStream, folder: File, databaseLimit: Long = DATABASE_BYTES): Unpacked? {
        folder.mkdirs()
        val database = File(folder, "song.db")
        listOf("", "-wal", "-shm", "-journal").forEach { File(folder, "song.db$it").delete() }
        var settings: String? = null
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "song.db" -> {
                        database.outputStream().use { copyCapped(zip, it, databaseLimit) }
                        found = true
                    }
                    "settings.json" -> settings = ByteArrayOutputStream().also { copyCapped(zip, it, TEXT_BYTES) }.toString(Charsets.UTF_8.name())
                }
                zip.closeEntry()
            }
        }
        return if (found) Unpacked(database, settings) else null
    }

    private fun copyCapped(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("a file in the backup is larger than $limit bytes")
            output.write(buffer, 0, n)
        }
    }

    /**
     * Their database, only read. Opened read and write all the same: it is our own copy in our cache, and SQLite may
     * need to make the small files a database written with write-ahead logging is read with.
     */
    class Theirs(file: File) : MetrolistImport.Db, AutoCloseable {
        private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)

        override fun tables(): Set<String> = buildSet {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }

        override fun columns(table: String): List<String> = buildList {
            db.rawQuery("PRAGMA table_info(\"${table.replace("\"", "\"\"")}\")", null).use { c ->
                val name = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(name))
            }
        }

        override fun userVersion(): Int = db.version

        override fun query(sql: String, args: List<Any?>, row: (List<Any?>) -> Unit) {
            db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> while (c.moveToNext()) row(values(c)) }
        }

        override fun exec(sql: String, args: List<Any?>): Int = throw UnsupportedOperationException("their database is only read")

        override fun close() = db.close()
    }

    /** Ours, inside the caller's transaction (MusicDatabase.transactionNow), so Room's screens follow when it ends. */
    class Ours(private val db: SupportSQLiteDatabase) : MetrolistImport.Db {
        override fun tables(): Set<String> = buildSet {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }

        override fun columns(table: String): List<String> = buildList {
            db.query("PRAGMA table_info(\"${table.replace("\"", "\"\"")}\")").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(name))
            }
        }

        override fun userVersion(): Int = db.version

        override fun query(sql: String, args: List<Any?>, row: (List<Any?>) -> Unit) {
            db.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c -> while (c.moveToNext()) row(values(c)) }
        }

        override fun exec(sql: String, args: List<Any?>): Int = db.compileStatement(sql).use { st ->
            args.forEachIndexed { i, a -> bind(st, i + 1, a) }
            // An insert another row stopped (OR IGNORE, NOT EXISTS) is -1 and changed nothing.
            if (sql.trimStart().startsWith("INSERT", ignoreCase = true)) (if (st.executeInsert() == -1L) 0 else 1)
            else st.executeUpdateDelete()
        }
    }

    private fun values(c: Cursor): List<Any?> = List(c.columnCount) { i ->
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
            else -> c.getString(i)
        }
    }

    private fun bind(st: SupportSQLiteProgram, i: Int, value: Any?) = when (value) {
        null -> st.bindNull(i)
        is Long -> st.bindLong(i, value)
        is Int -> st.bindLong(i, value.toLong())
        is Boolean -> st.bindLong(i, if (value) 1 else 0)
        is Double -> st.bindDouble(i, value)
        is Float -> st.bindDouble(i, value.toDouble())
        is ByteArray -> st.bindBlob(i, value)
        else -> st.bindString(i, value.toString())
    }
}
