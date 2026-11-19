/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes run in the order they were handed over, one at a time for each key, and keys that do not share
 * a lane do not wait for each other. A write handed over is queued at once, on the caller's thread, so
 * the order is the order of the calls, whatever threads run the writes after. A write that throws is
 * given to [onError] and the next one runs.
 */
class InOrder(
    private val scope: CoroutineScope,
    private val lanes: Int = 8,
    private val onError: (Throwable) -> Unit = {},
) {
    private val queues = List(lanes) { Channel<suspend () -> Unit>(Channel.UNLIMITED) }

    init {
        for (queue in queues) scope.launch {
            for (write in queue) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** The lane of [key]: one key always goes to the same lane. */
    fun lane(key: String): Int = Math.floorMod(key.hashCode(), lanes)

    /** Queues [write] after every write handed over for [key] before it, and returns at once. */
    fun send(key: String, write: suspend () -> Unit) {
        queues[lane(key)].trySend(write)
    }
}

/**
 * Every like, removelike and thumbs down the app sends YouTube Music for a song goes through here: one
 * at a time for each song, in the order they were tapped (#171). Each used to be a coroutine of its own
 * on syncCoroutine, three at a time, so a Dislike still on its way over a slow network, then Remove
 * dislike, could land the other way round, and the account kept the thumbs down the app showed lifted.
 * Two quick taps on the heart had the same race. Other songs do not wait (eight lanes keyed by the song),
 * so liking a big selection still sends three at a time, as syncCoroutine allows.
 */
object RatingWrites {
    private val queue = InOrder(CoroutineScope(SupervisorJob() + syncCoroutine)) {
        Log.w("RatingWrites", "A rating for YouTube Music could not be sent", it)
    }

    /** Sends [write] for the song [id] once the ones handed over for it before have gone. Returns at once. */
    fun send(id: String, write: suspend () -> Unit) = queue.send(id, write)
}
