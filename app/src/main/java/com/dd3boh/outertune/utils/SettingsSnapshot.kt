/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * The settings, held in memory: the file is read once, when the app starts, and every write is
 * in here before its edit returns.
 *
 * A read of a setting used to be `runBlocking(Dispatchers.IO) { data.first() }`, a thread hop and
 * a flow collection each time, on whichever thread asked. A launch made hundreds of them on the
 * main thread: nine in App.onCreate, and one for the first value of every rememberPreference on
 * the screen. The player asked twice a second whether it is in tablet mode. Now a read is a look
 * in a map.
 *
 * Every flow of the settings comes from here too, not only the reads. A copy kept current by a
 * collector of the file's own flow trails each write by a moment, and Home reads the setting it
 * watches at the very moment the change reaches it (HomeViewModel's refresh on a new source of
 * Quick picks): it would have built the page again for the old value. Here a change reaches no
 * collector before the copy holds it.
 *
 * Writes are the file's own, unchanged: DataStore takes them one at a time, in order, and the
 * copy follows that order, so a write whose caller resumes late never puts an older copy back.
 * Only edits made through this one store reach the copy, which is why the file is private to
 * DataStore.kt. A restore writes the file itself and restarts the app, which reads it again.
 */
class SettingsSnapshot(private val file: DataStore<Preferences>) : DataStore<Preferences> {
    private val held = MutableStateFlow(runBlocking(Dispatchers.IO) { file.data.first() })

    /** The order DataStore took each write in: given inside its transform, which it runs one at a time. */
    private val taken = AtomicLong()

    /** The last write the copy holds, in [taken]'s order. Under the lock on this. */
    private var shown = 0L

    /** Where a write that failed or lost its caller is followed up, away from that caller. */
    private val followUps = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The settings as they are now. */
    val current: Preferences get() = held.value

    override val data: StateFlow<Preferences> = held.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        var order = 0L
        val written = try {
            file.updateData { before -> transform(before).also { order = taken.incrementAndGet() } }
        } catch (e: Throwable) {
            // DataStore writes on its own coroutine while the caller waits for word back, so a
            // caller cancelled in that wait (a screen closing) gets an exception for a write that
            // lands all the same. Whatever went wrong, the file is asked again, behind the write,
            // and the copy takes what it says.
            followUps.launch { catchUp() }
            throw e
        }
        show(order, written)
        return written
    }

    /** Asks the file for what it holds, in turn with the writes, without writing anything. */
    private suspend fun catchUp() {
        var order = 0L
        val now = runCatching { file.updateData { it.also { order = taken.incrementAndGet() } } }.getOrNull() ?: return
        show(order, now)
    }

    private fun show(order: Long, settings: Preferences) = synchronized(this) {
        if (order > shown) {
            shown = order
            held.value = settings
        }
    }
}
