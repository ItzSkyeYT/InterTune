/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * One fetch of a song at a time: a second fetch of a song whose first is under way waits for it,
 * and finds what it kept. Fetches of different songs never wait on each other. Holds no address,
 * only who is fetching which song, and forgets a song as soon as nobody is.
 */
class OneFetchAtATime {

    private val running = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Runs [block] for song [id] once any fetch of it under way has finished. The wait can be
     * interrupted, as a load the player cancels interrupts the thread it runs on.
     */
    fun <T> run(id: String, block: () -> T): T {
        val lock = running.computeIfAbsent(id) { ReentrantLock() }
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
            if (!lock.hasQueuedThreads()) running.remove(id, lock)
        }
    }
}
