/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A step of finding a song that we gave up on ourselves, after [ms] with no answer. A socket
 * timeout as far as anything else can tell, so MusicService shows it as "Timeout" as it always
 * has; and never a word from YouTube, so it counts against no client and never trips the throttle.
 */
class StepTimedOut(val step: String, val ms: Long) : SocketTimeoutException("$step: no answer in ${StepLog.limit(ms)}")

/**
 * One network step with a deadline, and a second try on a fresh connection when the first has
 * not answered by [hedgeAfterMs].
 *
 * Starts `attempt(false)`. At [hedgeAfterMs], if it has not answered and [mayHedge] says so,
 * starts `attempt(true)` beside it, which is to make its request on a new connection. The first
 * answer that [settles] is returned and the other attempt cancelled. An answer that does not
 * settle (a connection that failed) waits for the other attempt while one is out, and is returned
 * as it is when none is. At [capMs] both are cancelled and [StepTimedOut] is thrown.
 *
 * Why: on 20 Oct 2026 a request on a connection the network had dropped never answered, while a
 * new connection answered at once, every time. OkHttp never retries a read once the request has
 * gone out, so waiting longer could not help; asking again could.
 *
 * The attempts run apart from the caller, so one that does not stop at once when cancelled (a
 * blocking read) never holds the caller past [capMs].
 */
suspend fun <T> hedged(
    step: String,
    hedgeAfterMs: Long,
    capMs: Long,
    mayHedge: () -> Boolean,
    settles: (T) -> Boolean = { true },
    onHedge: () -> Unit = {},
    attempt: suspend (fresh: Boolean) -> T,
): T {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val job = SupervisorJob()
    val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + job)
    val outcomes = Channel<Result<T>>(Channel.UNLIMITED)
    fun start(fresh: Boolean) = scope.launch {
        val outcome = try {
            Result.success(attempt(fresh))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        outcomes.send(outcome)
    }
    try {
        start(fresh = false)
        var out = 1
        var hedgeDue = hedgeAfterMs < capMs
        while (true) {
            val until = if (hedgeDue) hedgeAfterMs else capMs
            val left = until - elapsed()
            val got = if (left > 0) withTimeoutOrNull(left) { outcomes.receive() } else null
            if (got == null) {
                if (hedgeDue) {
                    hedgeDue = false
                    if (mayHedge()) {
                        onHedge()
                        start(fresh = true)
                        out++
                    }
                    continue
                }
                throw StepTimedOut(step, capMs)
            }
            out--
            val value = got.getOrNull()
            if (got.isSuccess && settles(value as T)) return value
            if (out == 0) return got.getOrThrow()
        }
    } finally {
        job.cancel()
    }
}

/**
 * Time a resolve is given on top of its limit: what a web client's address spent waiting out the
 * ads YouTube put before the song. That wait is deliberate, and not the network failing to answer.
 */
class ResolveAllowance(
    private val limitMs: Long = Long.MAX_VALUE / 4,
    private val startedNs: Long = System.nanoTime(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResolveAllowance>

    val extraMs = AtomicLong(0)

    /** What is left of the resolve's time, its ad waits included. */
    fun leftMs(): Long = limitMs + extraMs.get() - (System.nanoTime() - startedNs) / 1_000_000
}

/**
 * What is left of the time of the resolve this runs in, or null outside one. The walk reads it to
 * play an address it holds rather than be cut off with it: see YTPlayerUtils.resolveOnce.
 */
suspend fun resolveTimeLeft(): Long? = currentCoroutineContext()[ResolveAllowance]?.leftMs()

/** Adds [ms] to the allowance of the resolve this runs in, if any. */
suspend fun allowFor(ms: Long) {
    currentCoroutineContext()[ResolveAllowance]?.extraMs?.addAndGet(ms)
}

private val resolves = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * [work], the whole resolve of one song, given [limitMs] of waiting and its ad waits on top, then
 * cancelled and given up with [StepTimedOut]. Run apart from the caller, which is the player's
 * loading thread: a step that does not stop at once when cancelled finishes on its own, and the
 * player is told at once.
 */
suspend fun <T> withinResolveTime(
    limitMs: Long = RouteTimes.resolveMs,
    /** Carried to the work: what the player wants of this resolve, see YTPlayerUtils.RouteWish. */
    context: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    work: suspend () -> T,
): T {
    val allowance = ResolveAllowance(limitMs)
    val running = resolves.async(allowance + context) { work() }
    try {
        while (true) {
            val left = allowance.leftMs()
            if (left <= 0) throw StepTimedOut("resolve", limitMs)
            if (withTimeoutOrNull(left) { running.join(); true } == true) return running.await()
        }
    } finally {
        if (!running.isCompleted) running.cancel()
    }
}
