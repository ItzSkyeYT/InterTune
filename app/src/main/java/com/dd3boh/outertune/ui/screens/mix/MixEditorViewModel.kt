/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.toJson
import com.dd3boh.outertune.mix.edit.transitionSpecOf
import com.dd3boh.outertune.playback.automix.MixStores
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * The editor's page: loads the pair, holds the [MixEditor] and the preview, and keeps the spec and
 * the last undo steps in the saved state, so a turn of the phone or the process's end loses
 * nothing. Its two songs are the route's "from" and "to", by their exact ids.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class MixEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val fromId: String = saved.get<String>("from").orEmpty()
    val toId: String = saved.get<String>("to").orEmpty()

    val songA: StateFlow<Song?> = database.song(fromId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val songB: StateFlow<Song?> = database.song(toId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    sealed interface Phase {
        data class Loading(val stage: PairLoader.Stage) : Phase
        data class Failed(val side: Side, val offline: Boolean) : Phase
        data object Ready : Phase
    }

    /** What the page shows of the spec: everything that changes with an edit. */
    data class Ui(
        val spec: TransitionSpec,
        val lit: Preset?,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val dirty: Boolean,
    )

    val phase = MutableStateFlow<Phase>(Phase.Loading(PairLoader.Stage.READING_A))
    val ui = MutableStateFlow<Ui?>(null)

    /**
     * Which level of the editor is shown: Simple, a sheet over the player with a row per choice,
     * which every way in opens, or Advanced, the full page. Both edit the one spec here, so going
     * from one to the other loses nothing.
     */
    val simple: StateFlow<Boolean> = saved.getStateFlow(KEY_SIMPLE, true)

    fun showSimple(on: Boolean) {
        saved[KEY_SIMPLE] = on
    }

    var editor: MixEditor? = null
        private set
    var pair: LoadedPair? = null
        private set
    var preview: MixPreview? = null
        private set

    /** The player the preview paused, to play on when the page is left, unless something else started it since. */
    var pausedPlayer: androidx.media3.common.Player? = null

    /** Adjust's own undo step: the spec before it opened, and whether anything was changed in it. */
    var adjustBefore: TransitionSpec? = null
    var adjustEdited = false

    private var source: DataSource.Factory? = null
    private var levelOf: (String) -> Double = { 1.0 }
    private var volumeOf: () -> Float = { 1f }
    private var job: Job? = null
    private val cancelled = AtomicBoolean(false)

    /**
     * Starts reading the pair through the player's way to the songs, once. [levelOf] gives a song's
     * level as Mix's stage plays it now (its loudness normalisation, and the in-app volume above
     * 100%), [volume] the in-app volume the player plays at.
     */
    fun start(source: DataSource.Factory, levelOf: (String) -> Double, volume: () -> Float) {
        if (this.source != null) return
        this.source = source
        this.levelOf = levelOf
        this.volumeOf = volume
        load()
    }

    fun retry() = load()

    private fun load() {
        val source = source ?: return
        job?.cancel()
        cancelled.set(false)
        phase.value = Phase.Loading(PairLoader.Stage.READING_A)
        job = viewModelScope.launch {
            try {
                // A song the library has no row for is read by its id, titled by it.
                val a = songA.value ?: withTimeoutOrNull(ROW_WAIT_MS) { songA.first { it != null } }
                val b = songB.value ?: withTimeoutOrNull(ROW_WAIT_MS) { songB.first { it != null } }
                val fixes = withContext(Dispatchers.IO) { MixStores.beatFixes(context) }
                val inputA = input(fromId, a, withContext(Dispatchers.IO) { fixes.get(fromId) } ?: BeatFix.NONE)
                val inputB = input(toId, b, withContext(Dispatchers.IO) { fixes.get(toId) } ?: BeatFix.NONE)
                // Each song at its level as the phone plays it now, so the preview is heard as the
                // change will be, and Auto's proofs judge the sum the phone would play. At full, the
                // preview came in up to 13 dB louder than the music it paused, the two songs out of
                // their balance and the shared headroom worked out on a hotter sum.
                val levelA = withContext(Dispatchers.IO) { runCatching { levelOf(fromId) }.getOrDefault(1.0) }
                val levelB = withContext(Dispatchers.IO) { runCatching { levelOf(toId) }.getOrDefault(1.0) }
                val volume = runCatching { volumeOf() }.getOrDefault(1f)
                val loaded = withContext(Dispatchers.IO) {
                    PairLoader(source, EditorCache(context.cacheDir)) { id, end -> MixStores.analysed(context, id, end) }.load(
                        inputA, inputB, levelA, levelB,
                        stage = { phase.value = Phase.Loading(it) },
                        cancelled = { cancelled.get() },
                    )
                }
                val stored = withContext(Dispatchers.IO) { runCatching { MixStores.transitions(context).get(SongPair(fromId, toId)) }.getOrNull() }
                // A change made on parts waits for the parts: on bands it opens on Auto.
                val usable = stored?.takeIf { it.lanes == LaneSet.BANDS && it.isValid }
                val restoredSpec = saved.get<String>(KEY_SPEC)?.let(::transitionSpecOf)?.takeIf { it.pair == SongPair(fromId, toId) }
                val restoredUndo = saved.get<ArrayList<String>>(KEY_UNDO)?.mapNotNull(::transitionSpecOf).orEmpty()
                val e = MixEditor(loaded.facts, loaded.auto, usable, restoredUndo, restoredSpec)
                preview?.release()
                pair = loaded
                editor = e
                // The change as the phone will play it, from the engine's own render of the two copies.
                preview = EnginePreview(loaded.aCopy, loaded.bCopy, loaded.a.analysis, levelA, levelB, volume)
                publish()
                phase.value = Phase.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "The pair could not be read", e)
                val side = (e as? PairLoader.ReadFailed)?.side ?: Side.A
                phase.value = Phase.Failed(side, offline = !online())
            }
        }
    }

    private fun input(id: String, song: Song?, fix: BeatFix) = PairLoader.SongInput(
        id = id,
        title = song?.title ?: id,
        durationMs = (song?.song?.duration ?: -1) * 1000.0,
        albumId = song?.song?.albumId,
        fix = fix,
    )

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Hands the page and the preview the spec as it is now, and keeps it for the process's end. */
    fun publish() {
        val e = editor ?: return
        val spec = e.spec
        ui.value = Ui(spec, e.lit(), e.canUndo, e.canRedo, e.dirty)
        preview?.update(spec, e.fitStart(), e.fitEnd())
        saved[KEY_SPEC] = spec.toJson()
        // The undo steps only when they changed: a drag publishes on every frame it moves.
        val steps = e.undoSteps
        if (steps.size != keptUndoSize || steps.lastOrNull() !== keptUndoLast) {
            saved[KEY_UNDO] = ArrayList(steps.takeLast(MixEditor.KEPT_UNDO).map { it.toJson() })
            keptUndoSize = steps.size
            keptUndoLast = steps.lastOrNull()
        }
    }

    private var keptUndoSize = -1
    private var keptUndoLast: TransitionSpec? = null

    /** Runs one edit on the editor and shows it. */
    inline fun <T> edit(block: MixEditor.() -> T): T? {
        val e = editor ?: return null
        val result = e.block()
        publish()
        return result
    }

    /** Saves the change for the pair, and the songs' beat fixes, then calls [done] on the main thread. */
    fun save(done: (Boolean) -> Unit) {
        val e = editor ?: return
        val action = e.saveAction(System.currentTimeMillis())
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val store = MixStores.transitions(context)
                    when (action) {
                        is SaveAction.Forget -> store.delete(action.pair)
                        is SaveAction.Keep -> store.put(action.spec)
                    }
                    val fixes = MixStores.beatFixes(context)
                    action.fixes.forEach { (song, fix) -> fixes.put(song, fix) }
                }.onFailure { Log.w(TAG, "The change could not be saved", it) }.isSuccess
            }
            done(ok)
        }
    }

    override fun onCleared() {
        cancelled.set(true)
        preview?.release()
        preview = null
        pausedPlayer?.let { if (!it.isPlaying) it.play() }
        pausedPlayer = null
    }

    private companion object {
        const val TAG = "MixEditor"
        const val KEY_SPEC = "mix_spec"
        const val KEY_UNDO = "mix_undo"
        const val KEY_SIMPLE = "mix_simple"
        const val ROW_WAIT_MS = 2_000L
    }
}
