/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import java.util.concurrent.atomic.AtomicLong

/**
 * A value read from the database, kept until a table it was read from changes.
 *
 * Made for Discover's heard set (EngineLoader.known): every song the listener knows, read in one
 * query that took 0.24 s on this PC for a library of 76,635 songs (8,403 known), so about 0.5 to 1 s
 * on a phone, at every build of Home's Discover row and every Discover pick. Most builds come with
 * nothing changed since the one before.
 *
 * [watch] is handed the function to call when one of the tables changes (MusicDatabase hands it to
 * Room's InvalidationTracker) and runs once, at the first [get], on the thread that calls it: Room
 * asks for a background thread there. Nothing is kept until it has run, and nothing ever is if it
 * throws: each [get] then reads, as before.
 *
 * A change that lands while a read is under way leaves that read's value unkept, since it may have
 * been taken before the change: the next [get] reads again.
 */
class KeptUntilChanged<T : Any>(private val watch: (onChange: () -> Unit) -> Unit) {

    private class Kept<T>(val at: Long, val value: T)

    /** Bumped at every change: a value read while it moved is never kept. */
    private val changes = AtomicLong()

    @Volatile
    private var kept: Kept<T>? = null

    /** Null until the first [get]; then whether [watch] ran and changes will be heard. */
    @Volatile
    private var watching: Boolean? = null
    private val lock = Any()

    /** What is kept, or [read]'s answer, kept when nothing changed while it ran. */
    fun get(read: () -> T): T {
        if (watching == null) {
            synchronized(lock) {
                if (watching == null) watching = runCatching { watch(::changed) }.isSuccess
            }
        }
        if (watching != true) return read()
        val seen = changes.get()
        kept?.let { if (it.at == seen) return it.value }
        val fresh = read()
        if (changes.get() == seen) kept = Kept(seen, fresh)
        return fresh
    }

    /** One of the tables changed: what is kept goes, and so does any read under way. */
    fun changed() {
        changes.incrementAndGet()
        kept = null
    }
}
