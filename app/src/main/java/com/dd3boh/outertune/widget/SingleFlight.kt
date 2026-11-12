/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/**
 * A cache whose misses are drawn once, however many ask at the same moment.
 *
 * The widget's pictures are drawn while it is composed, and they are also drawn ahead of that, off
 * the main thread, so the composition finds them done (see WidgetStore.warm). Two callers asking
 * for the same picture then either find it kept, or the one that asked first draws it and the other
 * waits for that drawing: a picture is never drawn twice, and the main thread never draws one that
 * a worker is half way through.
 */
internal class SingleFlight<K : Any, V : Any> {
    private val drawing = ConcurrentHashMap<K, FutureTask<V?>>()

    /**
     * What [lookup] has for [key], else the drawing of it: [draw] runs here when nobody else is drawing
     * this key, and its result is handed to [store] before anyone waiting is let go. A drawing that
     * fails gives null to everyone waiting and is not kept, so the next to ask tries it again.
     */
    fun get(key: K, lookup: (K) -> V?, store: (K, V) -> Unit, draw: () -> V?): V? {
        lookup(key)?.let { return it }
        // Looks again once it holds the place: the one before it may have just put the picture away.
        val mine = FutureTask<V?> { lookup(key) ?: draw() }
        val other = drawing.putIfAbsent(key, mine)
        if (other != null) return runCatching { other.get() }.getOrNull()
        try {
            mine.run()
            val made = runCatching { mine.get() }.getOrNull()
            if (made != null) store(key, made)
            return made
        } finally {
            drawing.remove(key)
        }
    }
}
