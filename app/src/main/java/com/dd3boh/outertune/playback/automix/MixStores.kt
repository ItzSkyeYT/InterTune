/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.MixPlaylistsKey
import com.dd3boh.outertune.mix.edit.JsonFileTransitionStore
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.TransitionStore
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mix (Unreleased.AUTOMIX_EDITOR): the one store of saved transitions in the app, shared by the
 * playlist's pills, the queue's line, the editor and playback, so a change saved in one is seen by
 * the others at once. Its reads and writes block on a file: call them off the main thread.
 */
object MixStores {

    private const val TAG = "MixStores"

    @Volatile
    private var transitions: WatchedTransitionStore? = null

    private val _changes = MutableStateFlow(0)

    /** Goes up by one at every write, so whatever shows saved changes reads them again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun transitions(context: Context): TransitionStore = transitions ?: synchronized(this) {
        transitions ?: WatchedTransitionStore(
            JsonFileTransitionStore.at(context.applicationContext.filesDir) { message, cause ->
                // A damaged file is moved aside by the store; the pairs it held play Auto.
                Log.w(TAG, "saved transitions: $message", cause)
            },
        ) { _changes.value += 1 }.also { transitions = it }
    }

    private class WatchedTransitionStore(
        private val inner: TransitionStore,
        private val changed: () -> Unit,
    ) : TransitionStore by inner {
        override fun put(spec: TransitionSpec) = inner.put(spec).also { changed() }
        override fun delete(pair: SongPair): Boolean = inner.delete(pair).also { if (it) changed() }
        override fun clear(): Int = inner.clear().also { if (it > 0) changed() }
    }
}

/** Mix: a library playlist's Mix switch. Off for every playlist until it is switched on. */
object MixSwitches {

    fun isOn(on: Set<String>?, playlistId: String?): Boolean = playlistId != null && on != null && playlistId in on

    /** One edit of the set, so two switches turned at once both stay as turned. */
    suspend fun set(context: Context, playlistId: String, on: Boolean) {
        context.dataStore.edit { prefs ->
            val now = prefs[MixPlaylistsKey] ?: emptySet()
            prefs[MixPlaylistsKey] = if (on) now + playlistId else now - playlistId
        }
    }
}
