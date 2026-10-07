/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * A read whose caller waits for it only so long, for callers that have something to do without the
 * answer.
 *
 * The player's loader is the one it was written for. It read a stored row before opening every
 * song, with no limit on the wait, so when the database stopped answering on 6 Oct 2026
 * (experiments/bugs/playback-stopped-1835) every song sat buffering for as long as the process
 * lived, although the stream could have been opened without the row.
 *
 * The read is made on a thread of its own, because that is the only way to bound it: while SQLite
 * waits for a connection it clears the interrupt and waits on (SQLiteConnectionPool
 * .waitForConnection), so a read that is stuck cannot be called back, only left behind. Left
 * behind, it stays parked until the database answers, and what it then reads is dropped.
 *
 * While a read is out like that, the ones after it are not made at all and get null at once. So a
 * database that is stuck costs the first caller the bound and the callers after it nothing, and it
 * parks the threads of the readers that were already waiting, not one for every song from then on.
 * The first read after the late one has come back is made as usual.
 */
class BoundedRead(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val executor: Executor = readers,
    /** Told once for each read given up on, with the bound it was given, so it can be logged. */
    private val onStall: (Long) -> Unit = {},
) {
    /** The read that did not come back, for as long as it is still out. */
    private val waitingOn = AtomicReference<FutureTask<*>?>(null)

    /**
     * What [read] returns, or null when it had not returned within the bound. A caller that must
     * tell "no such row" from "no answer" cannot use this: both are null. What [read] throws is
     * thrown here.
     */
    fun <T : Any> orNull(read: () -> T?): T? {
        waitingOn.get()?.let { late ->
            if (!late.isDone) return null
            waitingOn.compareAndSet(late, null)
        }
        val answer = FutureTask(Callable { read() })
        executor.execute(answer)
        return try {
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            waitingOn.set(answer)
            onStall(timeoutMs)
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    companion object {
        /**
         * Two seconds. A stored row is read in a few milliseconds, so this is far outside anything
         * a database that works takes, and it is what the first song after a stall waits before
         * it is opened without its row.
         */
        const val TIMEOUT_MS = 2_000L

        /**
         * Threads as they are needed, and gone a minute after the last read. Not Room's own four:
         * all four were among the threads that were stuck.
         */
        private val readers: Executor = Executors.newCachedThreadPool { work ->
            Thread(work, "bounded-read").apply { isDaemon = true }
        }
    }
}
