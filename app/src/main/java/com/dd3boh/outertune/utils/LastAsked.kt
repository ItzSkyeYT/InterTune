/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

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
 * @param find the thing itself, or null when there is none to be had right now
 * @param instead what to hand out then
 */
internal class LastAsked<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val find: suspend (K) -> V?,
    private val instead: (K) -> V,
) {
    private class Asked<K, V>(val key: K) {
        val answer: SettableFuture<V> = SettableFuture.create()
        var work: Job? = null
    }

    private var last: Asked<K, V>? = null

    @Synchronized
    fun ask(key: K): ListenableFuture<V> {
        last?.let { if (it.key == key && !it.answer.isCancelled) return it.answer }
        last?.work?.cancel()

        val asked = Asked<K, V>(key)
        last = asked
        asked.work = scope.launch {
            try {
                val found = find(key)
                // forgotten before it is handed out, so nobody is given a placeholder as if it were kept
                if (found == null) forget(asked)
                asked.answer.set(found ?: instead(key))
            } catch (e: CancellationException) {
                forget(asked)
                asked.answer.cancel(false)
                throw e
            } catch (e: Throwable) {
                // caught here, or it would take the scope down and every later cover with it
                forget(asked)
                asked.answer.setException(e)
            }
        }.also { work ->
            // given up on before it had begun: none of the above ran
            work.invokeOnCompletion {
                if (!asked.answer.isDone) {
                    forget(asked)
                    asked.answer.cancel(false)
                }
            }
        }
        return asked.answer
    }

    @Synchronized
    private fun forget(asked: Asked<K, V>) {
        if (last === asked) last = null
    }
}
