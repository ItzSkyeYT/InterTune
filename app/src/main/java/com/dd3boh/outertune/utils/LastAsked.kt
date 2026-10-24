/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds something once for everybody who asks for it in a row, and keeps it for whoever asks again.
 *
 * Made for the cover the media session wants. media3 asks its bitmap loader for the playing song's
 * cover from two places at every song change, the session's metadata and the notification, and
 * again each time either is refreshed, which is at every play, pause and like. A loader that starts
 * over each time downloads a new cover twice, decodes it again for every tap, and answers late
 * every time, so the system is first told the song has no cover and then that it has one.
 *
 * Only the last thing asked for is kept, because only the playing song is ever asked for. Asking
 * for something else gives up on what is still being found: skipping through ten songs must not
 * download ten covers.
 *
 * Finding may take two steps, because the thing itself can be seconds away (a large cover on a slow
 * connection) while something lesser is at hand (the small one, on the phone already). [find] hands
 * the lesser one over first, which answers everybody who is waiting, and carries on. An answer is
 * given once, so when the thing itself arrives it cannot reach those who were answered already:
 * it is kept in place of the lesser one, [better] is told, and whoever asks from then on gets it at
 * once. If it does not arrive, the lesser one stays what is handed out, with no wait, and the thing
 * itself is tried for again behind it: when somebody asks, but not sooner than [retryAfterMs] after
 * the last try came to nothing (the notification is built again every time a poor connection
 * stalls and picks up, and a download started each time would take from the music what little
 * there is); by itself, after each of the waits in [retryDelaysMs] in turn; and at once when told
 * to [tryAgain], which is when the connection comes back.
 *
 * When nothing at all can be found, [instead] stands in. It is handed out as something lesser too:
 * whatever a later try finds takes its place, a lesser thing as well as the thing itself, and
 * [better] is told. It used to be handed out for good, and the lock screen, which asks once a song,
 * showed the stand-in to the end of the song even after the cover had come.
 *
 * @param find the thing itself, or null when there is none to be had right now. Before that it may
 *   hand over something lesser to answer with in the meantime.
 * @param instead what stands in when nothing at all could be found
 * @param better told when what is handed out has been replaced by something better, after an
 *   answer went out: the thing itself, or something lesser in the stand-in's place
 * @param retryAfterMs how long a try that came to nothing is left alone before an ask tries again
 * @param retryDelaysMs the waits before each try made by itself after one that came to nothing
 * @param now the time in milliseconds, for the tests to set
 */
internal class LastAsked<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val find: suspend (key: K, meanwhile: (V) -> Unit) -> V?,
    private val instead: (K) -> V,
    private val better: (K) -> Unit = {},
    private val retryAfterMs: Long = 0,
    private val retryDelaysMs: List<Long> = emptyList(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Asked<K, V>(val key: K) {
        /** Answers everybody who asked before anything was in hand. */
        val first: SettableFuture<V> = SettableFuture.create()

        /** What is handed out since something better took the place of the first answer. */
        var since: ListenableFuture<V>? = null

        /** What is handed out is not the thing itself, so it is tried for again. */
        var lesser = false

        /** What is handed out is the stand-in, which anything found takes the place of. */
        var standIn = false

        /** When the last try for the thing itself came to nothing. */
        var cameToNothingAt = 0L

        /** Tries made by itself since the last ask for a try at once. */
        var retries = 0

        /** A try at once was asked for while another was still running. */
        var again = false
        var work: Job? = null
        var waiting: Job? = null
    }

    private var last: Asked<K, V>? = null

    @Synchronized
    fun ask(key: K): ListenableFuture<V> {
        last?.let { asked ->
            if (asked.key == key && !asked.first.isCancelled) {
                // What is in hand goes out again as it is. If that is not the thing itself, it is
                // tried for again behind it once it has been left alone long enough.
                if (asked.lesser && asked.work?.isActive != true && now() - asked.cameToNothingAt >= retryAfterMs) {
                    asked.waiting?.cancel()
                    asked.work = look(asked)
                }
                return asked.since ?: asked.first
            }
        }
        last?.let { it.work?.cancel(); it.waiting?.cancel() }

        val asked = Asked<K, V>(key)
        last = asked
        asked.work = look(asked)
        return asked.first
    }

    /**
     * Tries at once for the thing last asked for, if what is handed out for it is lesser: the
     * connection is back, and the waits are for a connection that is not. A try still running is
     * followed straight away by another.
     */
    @Synchronized
    fun tryAgain() {
        val asked = last ?: return
        if (!asked.lesser || asked.first.isCancelled) return
        asked.retries = 0
        if (asked.work?.isActive == true) {
            asked.again = true
            return
        }
        asked.waiting?.cancel()
        asked.work = look(asked)
    }

    private fun look(asked: Asked<K, V>): Job = scope.launch {
        val self = coroutineContext[Job]
        try {
            val found = find(asked.key) { lesser -> handOut(asked, lesser, standIn = false) }
            if (found == null) {
                // With nothing handed out yet, the stand-in goes out, and is tried past like
                // anything lesser.
                if (!asked.first.isDone) handOut(asked, instead(asked.key), standIn = true)
                cameToNothing(asked, self)
            } else {
                arrived(asked, found)
            }
        } catch (e: CancellationException) {
            if (!asked.first.isDone) {
                forget(asked)
                asked.first.cancel(false)
            }
            throw e
        } catch (e: Throwable) {
            // caught here, or it would take the scope down and every later cover with it
            if (asked.first.isDone) {
                cameToNothing(asked, self)
            } else {
                forget(asked)
                asked.first.setException(e)
            }
        }
    }.also { work ->
        // given up on before it had begun: none of the above ran
        work.invokeOnCompletion {
            if (!asked.first.isDone) {
                forget(asked)
                asked.first.cancel(false)
            }
        }
    }

    /**
     * [value] goes out as something lesser: as the first answer, or in place of the stand-in. In
     * place of anything else it is no news (what is on the phone is found again at every try).
     */
    private fun handOut(asked: Asked<K, V>, value: V, standIn: Boolean) {
        val firstAnswer: Boolean
        val replaced: Boolean
        synchronized(this) {
            firstAnswer = !asked.first.isDone
            replaced = !firstAnswer && last === asked && asked.lesser && asked.standIn
            if (firstAnswer) {
                asked.lesser = true
                asked.standIn = standIn
            }
            if (replaced) {
                asked.since = Futures.immediateFuture(value)
                asked.standIn = standIn
            }
        }
        if (firstAnswer) asked.first.set(value)
        // told outside the lock: whoever is told asks again there and then
        if (replaced) better(asked.key)
    }

    /** The thing itself: the first answer, or in place of whatever lesser went out. */
    private fun arrived(asked: Asked<K, V>, found: V) {
        val replaced = synchronized(this) {
            if (asked.first.isDone && last === asked && asked.lesser) {
                asked.since = Futures.immediateFuture(found)
                asked.lesser = false
                asked.standIn = false
                asked.again = false
                asked.waiting?.cancel()
                true
            } else {
                false
            }
        }
        if (replaced) better(asked.key) else asked.first.set(found)
    }

    /** A try that found nothing better than what is out. The next one is lined up. */
    @Synchronized
    private fun cameToNothing(asked: Asked<K, V>, self: Job?) {
        asked.cameToNothingAt = now()
        if (asked.work === self) asked.work = null
        if (last !== asked || !asked.lesser) return
        if (asked.again) {
            asked.again = false
            asked.work = look(asked)
            return
        }
        val wait = retryDelaysMs.getOrNull(asked.retries++) ?: return
        asked.waiting?.cancel()
        asked.waiting = scope.launch {
            delay(wait)
            synchronized(this@LastAsked) {
                if (last === asked && asked.lesser && asked.work?.isActive != true) asked.work = look(asked)
            }
        }
    }

    @Synchronized
    private fun forget(asked: Asked<K, V>) {
        if (last === asked) last = null
    }
}
