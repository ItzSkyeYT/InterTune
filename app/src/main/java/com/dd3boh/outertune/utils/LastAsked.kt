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
 * download ten covers. And what could not be found is not kept, so the next asker tries again: a
 * cover that failed for want of a connection comes back when the connection does.
 *
 * Finding may take two steps, because the thing itself can be seconds away (a large cover on a slow
 * connection) while something lesser is at hand (the small one, on the phone already). [find] hands
 * the lesser one over first, which answers everybody who is waiting, and carries on. An answer is
 * given once, so when the thing itself arrives it cannot reach those who were answered already:
 * it is kept in place of the lesser one, [better] is told, and whoever asks from then on gets it at
 * once. If it does not arrive, the lesser one stays what is handed out, with no wait, and an ask
 * tries for the thing itself again behind it, but not sooner than [retryAfterMs] after the last
 * try came to nothing: the notification is built again every time a poor connection stalls and
 * picks up, and a download started each time would take from the music what little there is.
 *
 * @param find the thing itself, or null when there is none to be had right now. Before that it may
 *   hand over something lesser to answer with in the meantime.
 * @param instead what to hand out when there is nothing at all
 * @param better told when the thing itself has arrived after something lesser was handed out
 * @param retryAfterMs how long a try that came to nothing is left alone while something lesser is
 *   handed out
 * @param now the time in milliseconds, for the tests to set
 */
internal class LastAsked<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val find: suspend (key: K, meanwhile: (V) -> Unit) -> V?,
    private val instead: (K) -> V,
    private val better: (K) -> Unit = {},
    private val retryAfterMs: Long = 0,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Asked<K, V>(val key: K) {
        /** Answers everybody who asked before anything was in hand. */
        val first: SettableFuture<V> = SettableFuture.create()

        /** Something lesser went out in [first], and the thing itself has not taken its place. */
        var lesser = false

        /** The thing itself, once it has arrived after something lesser went out. */
        var real: ListenableFuture<V>? = null

        /** When the last try for the thing itself came to nothing, with something lesser out. */
        var cameToNothingAt = 0L
        var work: Job? = null
    }

    private var last: Asked<K, V>? = null

    @Synchronized
    fun ask(key: K): ListenableFuture<V> {
        last?.let { asked ->
            if (asked.key == key && !asked.first.isCancelled) {
                asked.real?.let { return it }
                // The thing itself could not be had the last time. What is at hand goes out again
                // as it is, and the thing itself is tried again behind it once it has been left
                // alone long enough.
                if (asked.lesser && asked.work?.isActive != true && now() - asked.cameToNothingAt >= retryAfterMs) {
                    asked.work = look(asked)
                }
                return asked.first
            }
        }
        last?.work?.cancel()

        val asked = Asked<K, V>(key)
        last = asked
        asked.work = look(asked)
        return asked.first
    }

    private fun look(asked: Asked<K, V>): Job = scope.launch {
        try {
            val found = find(asked.key) { lesser -> if (asked.first.set(lesser)) handedLesser(asked) }
            if (found == null) {
                // With something lesser handed out, that stays what is handed out and a later ask
                // tries again. With nothing, the asking is forgotten before it is answered, so
                // nobody is given a placeholder as if it were kept.
                if (!leftWithLesser(asked)) {
                    forget(asked)
                    asked.first.set(instead(asked.key))
                }
            } else if (!asked.first.set(found) && replaced(asked, found)) {
                // told outside the lock: whoever is told asks again there and then
                better(asked.key)
            }
        } catch (e: CancellationException) {
            forget(asked)
            asked.first.cancel(false)
            throw e
        } catch (e: Throwable) {
            // caught here, or it would take the scope down and every later cover with it
            if (!leftWithLesser(asked)) {
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

    @Synchronized
    private fun handedLesser(asked: Asked<K, V>) {
        asked.lesser = true
    }

    /** Whether something lesser is out for a try that has just come to nothing, which is noted. */
    @Synchronized
    private fun leftWithLesser(asked: Asked<K, V>): Boolean {
        if (asked.lesser) asked.cameToNothingAt = now()
        return asked.lesser
    }

    /** Whether [found] took the place of something lesser that is still what would be handed out. */
    @Synchronized
    private fun replaced(asked: Asked<K, V>, found: V): Boolean {
        if (!asked.lesser || last !== asked) return false
        asked.lesser = false
        asked.real = Futures.immediateFuture(found)
        return true
    }

    @Synchronized
    private fun forget(asked: Asked<K, V>) {
        if (last === asked) last = null
    }
}
