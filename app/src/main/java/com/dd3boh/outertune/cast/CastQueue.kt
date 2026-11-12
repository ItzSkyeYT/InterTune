/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/** Which songs of the queue the TV lists as coming next. */
object CastQueue {
    /**
     * The places in a queue of [size] songs of the (up to) [count] that play after the one at
     * [index], in order. They wrap round to the queue's start when [repeatAll], where Next does,
     * and never include the playing song itself: a queue of one has nothing coming.
     */
    fun upcoming(index: Int, size: Int, repeatAll: Boolean, count: Int = Chromecast.MAX_UP_NEXT): List<Int> {
        if (index !in 0 until size || count <= 0) return emptyList()
        val places = mutableListOf<Int>()
        var at = index
        while (places.size < count) {
            at++
            if (at >= size) {
                if (!repeatAll) break
                at = 0
            }
            if (at == index) break
            places += at
        }
        return places
    }
}
