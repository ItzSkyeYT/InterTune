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
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.BeatFixStore
import com.dd3boh.outertune.mix.edit.JsonFileBeatFixStore
import com.dd3boh.outertune.mix.edit.JsonFileTransitionStore
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.TransitionStore
import com.dd3boh.outertune.mix.pill.AutoChoice
import com.dd3boh.outertune.mix.pill.HeardChanges
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * A change to the saved transitions asked for by a tap (the pill's menu, Settings > Mixing >
     * Clear): written off the main thread and carried through when the page is left. A write the
     * disk refuses (full, or the file cannot be replaced) is logged and [failed] is told on the main
     * thread. Thrown, it left a coroutine nothing catches, and the app closed.
     */
    fun changeTransitions(scope: CoroutineScope, context: Context, failed: () -> Unit, change: (TransitionStore) -> Unit) {
        scope.launch(Dispatchers.IO + NonCancellable) {
            if (!tryChange(transitions(context), change)) withContext(Dispatchers.Main) { failed() }
        }
    }

    /** Runs [change] on [store]; false, and logged, when the store refuses it. */
    fun tryChange(store: TransitionStore, change: (TransitionStore) -> Unit): Boolean = try {
        change(store)
        true
    } catch (e: Exception) {
        Log.w(TAG, "the saved transitions could not be written", e)
        false
    }

    @Volatile
    private var fixes: BeatFixStore? = null

    /**
     * The listener's fixes to songs' beats (Fix the beat), which the editor writes and Auto reads.
     * One for the process, as for the transitions: a second file store would write over the first.
     */
    fun beatFixes(context: Context): BeatFixStore = fixes ?: synchronized(this) {
        fixes ?: JsonFileBeatFixStore.at(context.applicationContext.filesDir) { message, cause ->
            Log.w(TAG, "beat fixes: $message", cause)
        }.also { fixes = it }
    }

    @Volatile
    private var heard: HeardChanges? = null

    /**
     * What Auto did between two songs when the change was last heard, for the pills. Read from
     * cacheDir/mix/heard.json the first time, so call it off the main thread.
     */
    fun heard(context: Context): HeardChanges = heard ?: synchronized(this) {
        heard ?: run {
            val file = heardFile(context)
            val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            (text?.let { HeardChanges.decode(it) } ?: HeardChanges()).also { heard = it }
        }
    }

    /** Keeps what a change came to, when it belongs to the pair, and writes the file. Off the main thread. */
    fun heardChange(context: Context, pair: SongPair, choice: AutoChoice) {
        val memory = heard(context)
        if (!memory.put(pair, choice)) return
        _changes.value += 1
        runCatching {
            val file = heardFile(context)
            file.parentFile?.mkdirs()
            val fresh = File(file.path + ".new")
            fresh.writeText(memory.encode())
            if (!fresh.renameTo(file)) fresh.delete()
        }.onFailure { Log.w(TAG, "could not keep what was heard", it) }
    }

    private fun heardFile(context: Context) = File(context.applicationContext.cacheDir, "mix/heard.json")

    @Volatile
    private var analysis: MixAnalysisCache? = null

    /** What was found of each song's end and opening, for Auto's pick on the pills. In the cache: losing it costs a listen. */
    fun analysis(context: Context): MixAnalysisCache = analysis ?: synchronized(this) {
        analysis ?: MixAnalysisCache(File(context.applicationContext.cacheDir, "mix/analysis")).also { analysis = it }
    }

    /** Keeps what was found of [songId]'s end or opening, and has the pills look again. Off the main thread. */
    fun analysed(context: Context, songId: String, end: SongEnd) {
        if (analysis(context).put(songId, end)) _changes.value += 1
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
