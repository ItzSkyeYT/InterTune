/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Callers asking for the same key at the same time wait on one run instead of starting one each.
 *
 * The run belongs to [scope], not to whichever caller started it, so a caller that goes away does not
 * cut it short for the others. It is cancelled only when nobody is left waiting. Nothing is kept once
 * it finishes: the next call runs again, and any caching is the block's own business.
 */
class SharedLookup<K : Any, V>(private val scope: CoroutineScope) {

    private class Entry<V>(val run: Deferred<V>) {
        var waiters = 0
    }

    private val entries = HashMap<K, Entry<V>>()

    suspend fun get(key: K, block: suspend () -> V): V {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry(scope.async(start = CoroutineStart.LAZY) { block() }) }
                .also { it.waiters++ }
        }
        try {
            entry.run.start()
            return entry.run.await()
        } finally {
            synchronized(entries) {
                entry.waiters--
                val finished = entry.run.isCompleted
                if ((finished || entry.waiters == 0) && entries[key] === entry) entries.remove(key)
                if (!finished && entry.waiters == 0) entry.run.cancel()
            }
        }
    }

    /** How many callers are waiting on the run for [key], for tests. */
    internal fun waiting(key: K): Int = synchronized(entries) { entries[key]?.waiters ?: 0 }
}
