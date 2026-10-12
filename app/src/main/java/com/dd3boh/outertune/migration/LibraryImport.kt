/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.ErrorText
import com.dd3boh.outertune.utils.QueueToPlaylist
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The import that is under way, held for the life of the app rather than the screen.
 *
 * Matching a library is hundreds of paced searches, minutes of them, and nobody should have to
 * watch it. Leaving the screen does not stop it, coming back shows where it is, and a run that was
 * cancelled or lost its connection carries on from the first track it has no answer for.
 *
 * What it needs of the phone comes in through the constructor: reading the chosen file, the
 * search, and writing the playlists. The app hands it the real ones. A test hands it its own, and
 * runs a whole import with no phone, from the file to the playlists.
 */
@Singleton
class LibraryImport internal constructor(
    private val readFile: suspend (Uri) -> Picked,
    private val matcher: () -> ImportMatcher,
    private val makePlaylists: suspend (List<Pair<String, List<SongItem>>>) -> Created,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Inject
    constructor(@ApplicationContext context: Context, database: MusicDatabase) : this(
        readFile = { uri -> context.readPicked(uri) },
        matcher = { ImportMatcher(search = ImportSearch::songs) },
        makePlaylists = { lists -> database.makeImported(lists) },
    )

    enum class Stage { IDLE, READING, FAILED, MATCHING, STOPPED, OFFLINE, MATCHED, CREATING, CREATED }

    data class State(
        val stage: Stage = Stage.IDLE,
        val fileName: String? = null,
        val format: ExportFormat? = null,
        val playlists: Int = 0,
        val skippedRows: Int = 0,
        val progress: ImportRun.Snapshot? = null,
        val problem: ImportParse.Problem? = null,
        val detail: String? = null,
        val createdPlaylists: Int = 0,
        val createdSongs: Int = 0,
    )

    /** A file somebody chose: what it is called, and what is in it, or null when it could not be read. */
    class Picked(val name: String, val bytes: ByteArray?)

    /** What Create playlists made. */
    data class Created(val playlists: Int, val songs: Int)

    /**
     * Held for every change of import and of stage. The screen asks from the main thread while
     * the jobs answer from theirs, and a job that was told to stop may still be finishing.
     */
    private val lock = Any()

    /**
     * Goes up each time the import starts again from nothing: another file, Start over. A job
     * keeps the number it was started under, and one that finds the number has moved on says
     * nothing more, so a file that finished reading late cannot take the place of the one chosen
     * after it.
     */
    private var round = 0
    private var job: Job? = null

    @Volatile
    private var run: ImportRun? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun open(uri: Uri) = open { readFile(uri) }

    /** [open] for a file however it is read: what a test has in place of a Uri. */
    internal fun open(pick: suspend () -> Picked) {
        synchronized(lock) {
            val mine = startAgain(Stage.READING)
            job = scope.launch { readAndMatch(mine, pick) }
        }
    }

    private suspend fun readAndMatch(mine: Int, pick: suspend () -> Picked) {
        val picked = try {
            pick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the chosen file", e)
            Picked("", null)
        }
        val name = picked.name.ifEmpty { null }
        val bytes = picked.bytes
        if (bytes == null) {
            show(mine, State(stage = Stage.FAILED, fileName = name, problem = null))
            return
        }
        if (bytes.size > MAX_BYTES) {
            show(mine, State(stage = Stage.FAILED, fileName = name, problem = ImportParse.Problem.NOT_TEXT))
            return
        }
        val parsed =
            if (ImportFile.isZip(bytes)) ImportFile.parseArchive(bytes)
            else ImportFile.parse(ImportFile.decode(bytes), ImportFile.nameFromFile(picked.name))
        when (parsed) {
            is ImportParse.Failed ->
                show(mine, State(stage = Stage.FAILED, fileName = name, problem = parsed.problem, detail = parsed.detail))
            is ImportParse.Parsed -> {
                val run = ImportRun(parsed)
                synchronized(lock) {
                    // Another file chosen while this one was being read wins, and this one says nothing.
                    if (mine != round) return
                    this.run = run
                    _state.value = State(
                        stage = Stage.MATCHING,
                        fileName = name,
                        format = parsed.format,
                        playlists = parsed.playlists.size,
                        skippedRows = parsed.skippedRows,
                    )
                }
                match(run)
            }
        }
    }

    /** Carries on from the first track with no answer, once a cancelled run has finished stopping. */
    fun resume() {
        synchronized(lock) {
            val run = run ?: return
            // Only a run that has stopped. Continue tapped twice, the second tap landing before
            // the screen has caught up with the first, would otherwise start a second run beside
            // the first, and Cancel would stop only one of them.
            if (_state.value.stage !in CAN_CONTINUE) return
            _state.update { it.copy(stage = Stage.MATCHING) }
            val previous = job
            job = scope.launch {
                previous?.join()
                match(run)
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            val run = run ?: return
            if (_state.value.stage != Stage.MATCHING) return
            job?.cancel()
            setStage(Stage.STOPPED, run)
        }
    }

    /** Forgets the file and everything matched from it. Nothing already created is touched. */
    fun reset() {
        synchronized(lock) { startAgain(Stage.IDLE) }
    }

    fun pick(index: Int, song: SongItem) = decide { it.pick(index, song) }

    fun skip(index: Int) = decide { it.skip(index) }

    fun undecide(index: Int) = decide { it.undecide(index) }

    private fun decide(block: (ImportRun) -> Unit) {
        val run = run ?: return
        block(run)
        refresh(run)
    }

    /** One local playlist per playlist in the file, named as the file names it. */
    fun create() {
        synchronized(lock) {
            val run = run ?: return
            // Offered once every song has its answer, and not twice: a second tap while the first
            // is still writing would make every playlist again under another name.
            if (_state.value.stage != Stage.MATCHED) return
            setStage(Stage.CREATING, run)
            job = scope.launch {
                try {
                    val made = makePlaylists(run.playlistsToCreate())
                    synchronized(lock) {
                        if (run !== this@LibraryImport.run) return@launch
                        _state.update {
                            it.copy(stage = Stage.CREATED, createdPlaylists = made.playlists, createdSongs = made.songs)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not create the imported playlists", e)
                    setStage(Stage.MATCHED, run)
                }
            }
        }
    }

    private suspend fun match(run: ImportRun) {
        if (!setStage(Stage.MATCHING, run)) return
        try {
            // Progress only, never the stage: a result that lands just after Cancel must not
            // turn a stopped run back into a running one on screen.
            matcher().run(run.tracks, run::isDone) { index, outcome ->
                run.record(index, outcome)
                refresh(run)
            }
            setStage(Stage.MATCHED, run)
        } catch (e: SearchUnavailable) {
            // A run that was cancelled while its last try was failing stays as Cancel left it.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Stopped matching: YouTube could not be reached", ErrorText.forLog(e))
            setStage(Stage.OFFLINE, run)
        }
    }

    /**
     * Drops the import there is and whatever was working on it, and says [stage]. Under [lock].
     * The number it gives back is the new round's, for the job that starts it.
     */
    private fun startAgain(stage: Stage): Int {
        round++
        job?.cancel()
        job = null
        run = null
        _state.value = State(stage = stage)
        return round
    }

    /** What reading a file came to, unless another file or Start over has taken its place since. */
    private fun show(mine: Int, state: State) = synchronized(lock) {
        if (mine == round) _state.value = state
    }

    /**
     * Both of these ignore a run that is no longer the current one, which is what a cancelled job
     * finishing late after Start over or a new file would otherwise overwrite.
     */
    private fun setStage(stage: Stage, from: ImportRun): Boolean = synchronized(lock) {
        if (from !== run) return false
        _state.update { it.copy(stage = stage, progress = from.snapshot()) }
        true
    }

    private fun refresh(from: ImportRun) = synchronized(lock) {
        if (from === run) _state.update { it.copy(progress = from.snapshot()) }
    }

    companion object {
        /** Far above any real export: a 10,000 song Exportify file with every column is about 5 MB. */
        const val MAX_BYTES = 20 * 1024 * 1024

        /** The stages Continue is offered in. */
        private val CAN_CONTINUE = setOf(Stage.STOPPED, Stage.OFFLINE)
    }
}

private const val TAG = "LibraryImport"

/** The chosen file's name and what is in it, read through the system's file picker. */
private fun Context.readPicked(uri: Uri): LibraryImport.Picked {
    val name = displayName(uri) ?: uri.lastPathSegment ?: "Import"
    val bytes = try {
        contentResolver.openInputStream(uri)?.use { readCapped(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the chosen file", e)
        null
    }
    return LibraryImport.Picked(name, bytes)
}

private fun readCapped(input: java.io.InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() <= LibraryImport.MAX_BYTES) {
        val read = input.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun Context.displayName(uri: Uri): String? = try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (e: Exception) {
    null
}

/**
 * One local playlist for each of [lists], named as the file names it, and all of them or none: a
 * failure part of the way leaves the library as it was.
 */
private suspend fun MusicDatabase.makeImported(lists: List<Pair<String, List<SongItem>>>): LibraryImport.Created {
    val taken = playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
        .first().map { it.playlist.name }.toMutableList()
    var made = 0
    var songs = 0
    transactionNow {
        for ((wanted, items) in lists) {
            val name = QueueToPlaylist.defaultName(wanted, taken, wanted)
            taken += name
            val playlist = PlaylistEntity(
                name = name,
                browseId = null,
                bookmarkedAt = LocalDateTime.now(),
                isEditable = true,
                isLocal = true,
            )
            // Search results, so most have never been in the song table, and the
            // playlist's foreign key needs them there first. insert skips any that are.
            items.forEach { insert(it.toMediaMetadata()) }
            insert(playlist)
            QueueToPlaylist.positions(items.map { it.id }).forEach { (songId, position) ->
                insert(PlaylistSongMap(playlistId = playlist.id, songId = songId, position = position))
            }
            made++
            songs += items.size
        }
    }
    return LibraryImport.Created(made, songs)
}
