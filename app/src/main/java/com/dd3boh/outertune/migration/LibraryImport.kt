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
import com.dd3boh.outertune.utils.QueueToPlaylist
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 */
@Singleton
class LibraryImport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var job: Job? = null

    @Volatile
    private var run: ImportRun? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun open(uri: Uri) {
        job?.cancel()
        run = null
        _state.value = State(stage = Stage.READING)
        job = scope.launch {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "Import"
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the chosen file", e)
                null
            }
            // Another file chosen while this one was being read wins, and this one says nothing.
            ensureActive()
            if (bytes == null) {
                _state.value = State(stage = Stage.FAILED, fileName = name, problem = null)
                return@launch
            }
            if (bytes.size > MAX_BYTES) {
                _state.value = State(stage = Stage.FAILED, fileName = name, problem = ImportParse.Problem.NOT_TEXT)
                return@launch
            }
            val parsed =
                if (ImportFile.isZip(bytes)) ImportFile.parseArchive(bytes)
                else ImportFile.parse(ImportFile.decode(bytes), ImportFile.nameFromFile(name))
            when (parsed) {
                is ImportParse.Failed -> _state.value =
                    State(stage = Stage.FAILED, fileName = name, problem = parsed.problem, detail = parsed.detail)
                is ImportParse.Parsed -> {
                    // Parsing a large file takes a moment of its own, so the same again.
                    ensureActive()
                    run = ImportRun(parsed)
                    _state.value = State(
                        stage = Stage.MATCHING,
                        fileName = name,
                        format = parsed.format,
                        playlists = parsed.playlists.size,
                        skippedRows = parsed.skippedRows,
                    )
                    match()
                }
            }
        }
    }

    /** Carries on from the first track with no answer, once a cancelled run has finished stopping. */
    fun resume() {
        if (run == null) return
        val previous = job
        job = scope.launch {
            previous?.join()
            match()
        }
    }

    fun cancel() {
        job?.cancel()
        setStage(Stage.STOPPED)
    }

    /** Forgets the file and everything matched from it. Nothing already created is touched. */
    fun reset() {
        job?.cancel()
        run = null
        _state.value = State()
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
        val run = run ?: return
        if (job?.isActive == true) return
        setStage(Stage.CREATING)
        job = scope.launch {
            val lists = run.playlistsToCreate()
            val taken = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
                .first().map { it.playlist.name }.toMutableList()
            var made = 0
            var songs = 0
            try {
                database.transactionNow {
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
                _state.update { it.copy(stage = Stage.CREATED, createdPlaylists = made, createdSongs = songs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not create the imported playlists", e)
                setStage(Stage.MATCHED)
            }
        }
    }

    private suspend fun match() {
        val run = run ?: return
        setStage(Stage.MATCHING, run)
        val matcher = ImportMatcher(search = { query ->
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
        })
        try {
            // Progress only, never the stage: a result that lands just after Cancel must not
            // turn a stopped run back into a running one on screen.
            matcher.run(run.tracks, run::isDone) { index, outcome ->
                run.record(index, outcome)
                refresh(run)
            }
            setStage(Stage.MATCHED, run)
        } catch (e: SearchUnavailable) {
            Log.w(TAG, "Stopped matching: YouTube could not be reached", e)
            setStage(Stage.OFFLINE, run)
        }
    }

    /**
     * Both of these ignore a run that is no longer the current one, which is what a cancelled job
     * finishing late after Start over or a new file would otherwise overwrite.
     */
    private fun setStage(stage: Stage, from: ImportRun? = run) {
        if (from == null || from !== run) return
        _state.update { it.copy(stage = stage, progress = from.snapshot()) }
    }

    private fun refresh(from: ImportRun) {
        if (from !== run) return
        _state.update { it.copy(progress = from.snapshot()) }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= MAX_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "LibraryImport"

        /** Far above any real export: a 10,000 song Exportify file with every column is about 5 MB. */
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}
