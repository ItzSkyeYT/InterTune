/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The library's database as files: the copy a backup takes of it while the app goes on writing,
 * and the swap a restore makes.
 *
 * Android keeps song.db in write-ahead mode. A commit goes into song.db-wal, and SQLite moves those
 * pages into song.db at a checkpoint, which a commit can start. A copy of song.db read while that
 * happens holds some pages from before a change and some from after it: a b-tree that does not
 * hold together, which the restore's integrity check refuses, or rows out of step, which it does
 * not see (4 Nov 2026). Plain Java, so BackupCopyTest and RestoreSwapTest run it against SQLite.
 */
object DatabaseFiles {
    /**
     * Held while a backup copies the database and while a restore puts one in its place, so that
     * none of them runs in the middle of another: Backup, the scheduled backup and Restore have
     * guards of their own, and the scheduled one's knows nothing of the other two.
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
     * through one connection, whose checkpoint is over before anything else can use it, but the
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

    /**
     * Puts [from] in the place of [to] in one step: a rename within the folder, which leaves [to]
     * either as it was or as [from], never part of each. The restore used to copy over song.db,
     * which empties the file before it writes: a process that died in the second the copy took
     * left a cut-off song.db, which SQLite's open helper deletes as damaged, and the library came
     * back empty (4 Nov 2026).
     *
     * Every connection to [to] has to be closed first. Its log, index and journal go before the
     * rename, never after: they belong to the file being replaced, and SQLite would replay a log
     * left beside the new file into it. A process that dies between the two leaves the old
     * library, whose log its close has folded in. [from] has to be whole on its own ([isWhole]).
     */
    fun replace(from: File, to: File) {
        if (!isWhole(from)) throw IOException("${from.name} is not whole on its own, so it was not put in place of ${to.name}")
        // On the disk before its name changes, so that a power cut just after the rename cannot
        // leave the new name on bytes that never got there.
        RandomAccessFile(from, "rw").use { it.fd.sync() }
        for (suffix in SIDECARS) {
            val stale = sidecar(to, suffix)
            if (stale.exists() && !stale.delete()) {
                throw IOException("Could not delete ${stale.name}, so ${to.name} was left as it was")
            }
        }
        SIDECARS.forEach { sidecar(from, it).delete() }
        if (!from.renameTo(to)) throw IOException("Could not move ${from.name} to ${to.name}")
    }

    private fun copyFile(from: File, to: File) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output -> input.copyTo(output, BUFFER_BYTES) }
        }
    }

    private const val BUFFER_BYTES = 1 shl 16
}
