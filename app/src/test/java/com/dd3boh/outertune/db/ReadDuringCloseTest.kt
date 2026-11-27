/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.util.performSuspending
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

/**
 * A restore closes song.db while the app still reads it, and two reads of 0.12.11 stopped the app
 * there (27 Nov 2026, experiments/bugs/issue-286 and issue-287).
 *
 * #286, on the main thread: a Flow's next read. Room first asks, on the thread that reads, whether
 * that thread is in a transaction (DBUtil.performSuspending), and its answer is "the database is
 * open, and it says so". The restore's close, on another thread, came between the two halves, and
 * the closed SQLiteDatabase threw "attempt to re-open an already-closed object". The trace's
 * android.support.v4.media.session frame is not the media session: R8 moved Room's
 * performSuspending into the class it renamed MediaSessionCompat to (0.12.11's mapping).
 *
 * #287, on Room's thread: the saved queues the player's service reads on its offload threads
 * (QueueDao.getQueueSongs), already under way when the database closed. Its next statement found
 * the connection closed (SupportSQLiteConnection.prepare: "connection is closed"). Nothing can tell
 * a read in the middle of its statements that the database is going, so the restore now waits for
 * the service's work to end before it closes, as the last two tests read from the source.
 *
 * The first three run Room itself, the 2.8.3 this build ships, on an open helper whose database
 * closes as the restore's did: open when Room looks, closed when it asks. The read never gets past
 * the question, so no schema is needed.
 */
class ReadDuringCloseTest {

    /** song.db as Room's open helper hands it out: open until it is closed, then as Android's closed SQLiteDatabase. */
    private class Library {
        @Volatile var closed = false

        /** Runs when the database is asked whether this thread is in a transaction, before it answers. */
        @Volatile var asked: () -> Unit = {}

        val database = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { proxy, method, args ->
            when (method.name) {
                "isOpen" -> !closed
                "inTransaction" -> {
                    asked()
                    // What SQLiteClosable.acquireReference throws once the last reference has gone.
                    if (closed) throw IllegalStateException("attempt to re-open an already-closed object: SQLiteDatabase: $PATH")
                    false
                }
                "close" -> { closed = true; null }
                "getPath" -> PATH
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "SQLiteDatabase: $PATH"
                else -> throw AssertionError("Room asked song.db for ${method.name}, which this test does not answer")
            }
        } as SupportSQLiteDatabase

        val helper = object : SupportSQLiteOpenHelper {
            override val databaseName: String = InternalDatabase.DB_NAME
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {}
            override val writableDatabase: SupportSQLiteDatabase get() = database
            override val readableDatabase: SupportSQLiteDatabase get() = database
            override fun close() { closed = true }
        }
    }

    /**
     * The app's database class on [library], as Room has it once song.db is open: its connection
     * manager holding the database it looks at for being open. That one is set by hand, since
     * opening for real checks the whole schema.
     */
    private fun room(library: Library): InternalDatabase {
        val room = Room.databaseBuilder(ContextWrapper(null), InternalDatabase::class.java, InternalDatabase.DB_NAME)
            .openHelperFactory(object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration) = library.helper
            })
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        val manager = RoomDatabase::class.java.getDeclaredField("connectionManager").apply { isAccessible = true }.get(room)
        manager.javaClass.getDeclaredField("supportDatabase").apply { isAccessible = true }.set(manager, library.database)
        assertTrue("Room sees song.db open", room.isOpen)
        return room
    }

    /** The restore's close, on a thread of its own as in the app, between Room's look and its question. */
    private fun closesWhenAsked(library: Library, room: InternalDatabase) {
        library.asked = {
            library.asked = {}
            thread { room.close() }.join()
        }
    }

    @Test
    fun `the restore's close comes between Room's look and its question, and the answer is no transaction`() {
        val library = Library()
        val room = room(library)
        closesWhenAsked(library, room)
        assertFalse(room.inTransaction())
        assertTrue("the close went through", library.closed)
    }

    @Test
    fun `a Flow's next read in that moment ends as a cancellation, not as a crash on the thread that reads`() {
        val library = Library()
        val room = room(library)
        closesWhenAsked(library, room)
        // What every Flow query and suspend read of Room runs first, on the collecting thread: the frame of #286.
        val read = runCatching {
            runBlocking { performSuspending(room, true, false) { throw AssertionError("the read ran on a closed database") } }
        }.exceptionOrNull()
        assertTrue("$read", read is CancellationException)
        assertTrue("the close went through", library.closed)
    }

    @Test
    fun `before any close was asked for, a database found closed is still an error`() {
        val library = Library()
        val room = room(library)
        // Closed under Room by something else, which is not for this guard to hide.
        library.asked = { library.closed = true }
        assertThrows(IllegalStateException::class.java) { room.inTransaction() }
    }

    // ---- #287: the restore closes only once the player's service has ended its work, read from the source ----

    private val main = "src/main/java/com/dd3boh/outertune"

    @Test
    fun `the restore waits for what the player's service still runs, for a bounded time, before the database closes`() {
        val restore = File("$main/viewmodels/BackupRestoreViewModel.kt").readText().substringAfter("fun restoreValidatedDatabase(")
        val stop = restore.indexOf("MusicService.alive()?.stopForRestore()")
        val wait = restore.indexOf("serviceWork.joinAll()")
        val close = restore.indexOf("database.close()")
        assertTrue("stop $stop, wait $wait, close $close", stop in 0 until wait && wait < close)
        assertTrue("a job that does not end does not hold the restore for good", "withTimeoutOrNull(SERVICE_WORK_WAIT_MS) { serviceWork.joinAll() }" in restore)
    }

    @Test
    fun `the service ends its observers and reads, the offload threads' among them, and hands over what is still running`() {
        val service = File("$main/playback/MusicService.kt").readText()
        val stop = service.substringAfter("fun stopForRestore(): List<Job> {", "").substringBefore("\n    }\n")
        assertTrue("stopForRestore hands back the jobs to wait for", stop.isNotEmpty())
        assertTrue(stop, "activePlayer.pause()" in stop)
        // The service's own scope (its Flows on the main thread) and offloadScope (the saved queues' read in #287).
        assertTrue(stop, "listOf(scope, offloadScope).map { it.coroutineContext.job.apply { cancel() } }" in stop)
        // What is writing on lastingScope, the queue's save among it, lands before the close and is not stopped.
        assertTrue(stop, "lastingScope.coroutineContext.job.children" in stop)
    }

    private companion object {
        const val PATH = "/data/user/0/dev.skye.intertune/databases/song.db"
    }
}
