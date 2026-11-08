/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import kotlinx.coroutines.delay
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
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
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
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.playback.BatteryUse
import com.dd3boh.outertune.playback.automix.KeptChanges
import com.dd3boh.outertune.playback.automix.MixStores
import com.dd3boh.outertune.playback.batteryModeNow
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.mix.WholeSongGrid
import com.dd3boh.outertune.mix.MixCode
import com.dd3boh.outertune.playback.automix.AutomixPace
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

    /** Each song's level as the phone plays it now, which the preview and the change kept on Save are heard at. */
    private var levels: Pair<Double, Double> = 1.0 to 1.0
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

    /**
     * Reads a song whole and keeps its grid, as playback does, only with the player's cache on (as
     * sized at start): the bytes read here are then the ones it plays from.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun wholeReader(source: DataSource.Factory): ((String, Double, () -> Boolean) -> WholeSongGrid?)? {
        val cacheOn = BatteryUse.songCacheMb(context.dataStore[MaxSongCacheSizeKey], context.batteryModeNow()) != 0
        if (!cacheOn) return null
        val decoder = com.dd3boh.outertune.playback.automix.HeadDecoder(source)
        return read@{ id, durationMs, cancelled ->
            // A song longer than a whole read (a DJ set) keeps its windows as they are.
            if (durationMs > WholeSongGrid.MAX_SECONDS * 1000) return@read null
            val whole = decoder.read(id, cancelled, wholeSeconds = WholeSongGrid.MAX_SECONDS).whole
            if (whole == null || (durationMs > 0 && whole.seconds * 1000 < durationMs - 5_000)) null
            else WholeSongGrid.analyse(whole)?.also { MixStores.wholeSongs(context).put(id, it) }
        }
    }

    /** Whether [songId]'s grid read from the whole song is locked; null where the song was never read whole. */
    suspend fun wholeLocked(songId: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching { MixStores.wholeSongs(context).get(songId)?.locked }.getOrNull()
    }

    /** Locks or unlocks [songId]'s whole-song grid against any analysis; [done] is told what it is now, on the main thread. */
    fun lockWhole(songId: String, locked: Boolean, done: (Boolean?) -> Unit) {
        viewModelScope.launch {
            val now = withContext(Dispatchers.IO) {
                runCatching { if (MixStores.wholeSongs(context).lock(songId, locked)) locked else MixStores.wholeSongs(context).get(songId)?.locked }.getOrNull()
            }
            done(now)
        }
    }

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
                // This phone's pace as playback keeps it, the Pixel 5's guess until it has one, as
                // playback does: how far before the end it can hold a change in time.
                val pace = withContext(Dispatchers.IO) { AutomixPace.read(context.filesDir) } ?: AutomixPace()
                val loaded = withContext(Dispatchers.IO) {
                    PairLoader(source, EditorCache(context.cacheDir, MixCode.ANALYSIS, MixCode.AUTO), { id, end -> MixStores.analysed(context, id, end) }, { id -> MixStores.wholeSongs(context).get(id) }, wholeReader(source)).load(
                        inputA, inputB, levelA, levelB, pace,
                        stage = { phase.value = Phase.Loading(it) },
                        cancelled = { cancelled.get() },
                    )
                }
                val stored = withContext(Dispatchers.IO) { runCatching { MixStores.transitions(context).get(SongPair(fromId, toId)) }.getOrNull() }
                // A change made on parts opens on them where both songs' parts are at hand; without
                // them it waits for the parts, and the page opens on Auto.
                val usable = MixEditor.openable(
                    stored?.takeIf { (it.lanes == LaneSet.BANDS || (it.lanes == LaneSet.PARTS && loaded.partsReady)) && it.isValid },
                    loaded.auto, loaded.facts,
                )
                val restoredSpec = saved.get<String>(KEY_SPEC)?.let(::transitionSpecOf)?.takeIf { it.pair == SongPair(fromId, toId) }
                val restoredUndo = saved.get<ArrayList<String>>(KEY_UNDO)?.mapNotNull(::transitionSpecOf).orEmpty()
                // Where both songs' parts are at hand the page is on parts, Auto's pick and the saved
                // change opened on them (bands to parts: Presets.onParts), so it opens unchanged.
                val onParts = loaded.partsReady
                val e = MixEditor(
                    loaded.facts,
                    if (onParts) Presets.onParts(loaded.auto) else loaded.auto,
                    if (onParts) usable?.let(Presets::onParts) else usable,
                    restoredUndo,
                    if (onParts) restoredSpec?.let(Presets::onParts) else restoredSpec,
                )
                preview?.release()
                pair = loaded
                levels = levelA to levelB
                editor = e
                // The change as the phone will play it, from the engine's own render of the two copies.
                preview = EnginePreview(loaded.aCopy, loaded.bCopy, loaded.a.analysis, levelA, levelB, volume)
                publish()
                phase.value = Phase.Ready
                // A change saved before changes were kept, or kept on bands before the parts came:
                // kept now, as the page plays it, so the playlist plays it without a render.
                if (stored != null && !stored.plain) {
                    val keep = withContext(Dispatchers.IO) {
                        runCatching { !KeptChanges(context.noBackupFilesDir).holds(fromId, toId, stored, loaded.partsReady) }.getOrDefault(false)
                    }
                    if (keep) keepRendered(SongPair(fromId, toId))
                }
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
        scheduleAutosave()
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

    /** The save a moment after the last edit (his ask of 8 Nov 2026: "make it so the transitions AUTOSAVE"). */
    private var autosave: Job? = null

    /** A change saved since it was last kept as rendered for the playlist (KeptChanges). */
    private var keptStale = false

    /** Saves the page's change [AUTOSAVE_AFTER_MS] after the last edit, where it has one. */
    private fun scheduleAutosave() {
        val e = editor ?: return
        if (!e.dirty) return
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(AUTOSAVE_AFTER_MS)
            saveNow()
        }
    }

    /** Writes the page's change and the songs' beat fixes where anything changed; true when nothing is left unsaved. */
    private suspend fun saveNow(): Boolean {
        val e = editor ?: return false
        if (!e.dirty) return true
        val written = e.spec
        val action = e.saveAction(System.currentTimeMillis())
        val ok = withContext(Dispatchers.IO) { write(action) }
        if (ok) {
            e.markSaved(written)
            keptStale = true
            publish()
        }
        return ok
    }

    /** The change for the pair, and the songs' beat fixes, into their stores. Blocking. */
    private fun write(action: SaveAction): Boolean = runCatching {
        val store = MixStores.transitions(context)
        when (action) {
            is SaveAction.Forget -> {
                store.delete(action.pair)
                KeptChanges(context.noBackupFilesDir).delete(action.pair.from, action.pair.to)
            }
            is SaveAction.Keep -> store.put(action.spec)
        }
        val fixes = MixStores.beatFixes(context)
        action.fixes.forEach { (song, fix) -> fixes.put(song, fix) }
    }.onFailure { Log.w(TAG, "The change could not be saved", it) }.isSuccess

    /** Save: the change saved now and kept as rendered, then [done] on the main thread. */
    fun save(done: (Boolean) -> Unit) {
        autosave?.cancel()
        viewModelScope.launch {
            val ok = saveNow()
            if (ok) keepIfStale()
            done(ok)
        }
    }

    /** The change kept as rendered for the playlist, where it was saved since it last was. */
    private fun keepIfStale() {
        if (!keptStale) return
        keptStale = false
        keepRendered(SongPair(fromId, toId))
    }

    /**
     * The change just saved for [saved] rendered once more as the page plays it, and kept beside it
     * (KeptChanges), so the playlist plays what was heard here without rendering it again. In the
     * background, since Save closes the page. A plain change has nothing to render.
     */
    private fun keepRendered(saved: SongPair) {
        val loaded = pair ?: return
        val (levelA, levelB) = levels
        KeptChanges.scope.launch { keepRender(context, loaded, levelA, levelB, saved) }
    }

    override fun onCleared() {
        cancelled.set(true)
        autosave?.cancel()
        // Leaving the page keeps what it had: what was not saved yet is saved, and the change kept
        // as rendered, past the page, which is gone.
        editor?.let { e ->
            val pending = if (e.dirty) e.saveAction(System.currentTimeMillis()) else null
            if (pending != null || keptStale) {
                keptStale = false
                val pair = SongPair(fromId, toId)
                KeptChanges.scope.launch {
                    if (pending != null) write(pending)
                    keepRendered(pair)
                }
            }
        }
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

        /** How long after the last edit the change is saved by itself. */
        const val AUTOSAVE_AFTER_MS = 1_500L
    }
}
