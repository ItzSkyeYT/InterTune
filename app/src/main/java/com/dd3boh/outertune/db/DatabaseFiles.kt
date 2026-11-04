/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The library's database as files: the copy a backup takes of it while the app goes on writing.
 *
 * Android keeps song.db in write-ahead mode. A commit goes into song.db-wal, and SQLite moves those
 * pages into song.db at a checkpoint, which a commit can start. A copy of song.db read while that
 * happens holds some pages from before a change and some from after it: a b-tree that does not
 * hold together, which the restore's integrity check refuses, or rows out of step, which it does
 * not see (4 Nov 2026). Plain Java, so BackupCopyTest runs it against SQLite.
 */
object DatabaseFiles {
    /**
     * Held while a backup copies the database, so that two backups (Backup and the scheduled one,
     * which have guards of their own that know nothing of each other) never copy at once.
     */
    val lock = Any()

    /** What SQLite keeps beside a database: its write-ahead log, the log's index, and the rollback journal. */
    val SIDECARS = listOf("-wal", "-shm", "-journal")

    fun sidecar(db: File, suffix: String) = File(db.path + suffix)

    /**
     * Copies [db] into [into] as it stood at one moment: the moment [holdingWriteLock] took the
     * database's write lock, which it holds while the files are copied (the app passes a Room
     * transaction). No commit lands during the copy, so writers wait for as long as two plain file
     * copies take, and nobody else.
     *
     * The log is copied with the file, and SQLite's own advice is to keep the two together. The
     * lock stops commits, not checkpoints: SQLite lets one run while another connection holds the
     * write lock, started by the commit just before or by anyone who asks for one, and it moves
     * pages into song.db as it is being read. Every page it can move is in the log, though, and
     * opened together, the copied pair comes back as what was committed when the lock was taken,
     * the way SQLite rebuilds a database after a crash. On Android every write of the app goes
     * through one connection, which finishes its checkpoint before it lets go of the lock, but the
     * copy does not depend on that.
     *
     * The copy is whole once SQLite has opened it and folded its log in ([isWhole] says when it
     * has). [copyFile] is there for the test that runs a checkpoint half way through a copy.
     */
    fun copy(
        db: File,
        into: File,
        holdingWriteLock: (copy: () -> Unit) -> Unit,
        copyFile: (from: File, to: File) -> Unit = ::copyFile,
    ) {
        into.parentFile?.mkdirs()
        // Whatever an earlier copy left here belongs to another moment.
        (listOf(into) + SIDECARS.map { sidecar(into, it) }).forEach { it.delete() }
        holdingWriteLock {
            copyFile(db, into)
            val log = sidecar(db, "-wal")
            if (log.length() > 0) copyFile(log, sidecar(into, "-wal"))
        }
    }

    /**
     * Whether [db] holds all of itself: a file with something in it, and no write-ahead log or
     * rollback journal beside it that has anything left in it. An index (-shm) is only an index.
     */
    fun isWhole(db: File): Boolean =
        db.isFile && db.length() > 0 && listOf("-wal", "-journal").none { sidecar(db, it).length() > 0 }

    private fun copyFile(from: File, to: File) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output -> input.copyTo(output, BUFFER_BYTES) }
        }
    }

    private const val BUFFER_BYTES = 1 shl 16
}
