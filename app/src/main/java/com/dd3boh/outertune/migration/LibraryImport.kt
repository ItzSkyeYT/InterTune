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
import com.dd3boh.outertune.utils.Throttle
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The import that is under way, held for the life of the app rather than the screen, and kept on
 * the phone for longer than that.
 *
 * Matching a library is hundreds of paced searches, minutes of them, and nobody should have to
 * watch it. Leaving the screen does not stop it, coming back shows where it is, and a run that was
 * cancelled, lost its connection or was turned away by YouTube carries on from the first track it
 * has no answer for.
 *
 * Minutes is also long enough for Android to close an app that nobody is looking at. So what the
 * file held, what each lookup came to and every pick and leave-out are written down as they come
 * ([ImportStore]). The next time the import screen is opened the import is there again as it was
 * left, stopped, and Continue looks up what is left. It is forgotten when it is over: Start over,
 * another file, or the playlists made. Cancel does not forget it, as it only stops the lookups
 * and says that what was found is kept.
 *
 * What it needs of the phone comes in through the constructor: where it is kept, reading the
 * chosen file, the search, and writing the playlists. The app hands it the real ones. A test hands
 * it its own, and runs a whole import with no phone, from the file to the playlists.
 */
@Singleton
class LibraryImport internal constructor(
    private val store: ImportStore,
    private val readFile: suspend (Uri) -> Picked,
    private val matcher: () -> ImportMatcher,
    private val makePlaylists: suspend (List<Pair<String, List<SongItem>>>) -> Created,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How long a change waits to be written down, with whatever else changes meanwhile. */
    private val saveAfter: suspend () -> Unit = { delay(SAVE_AFTER_MS) },
) {
    @Inject
    constructor(@ApplicationContext context: Context, database: MusicDatabase) : this(
        store = ImportStore(File(context.filesDir, FOLDER)),
        readFile = { uri -> context.readPicked(uri) },
        matcher = { ImportMatcher(search = ImportSearch::songs, backingOff = { Throttle.isBlocked }) },
        makePlaylists = { lists -> database.makeImported(lists) },
    )

    /**
     * Three of these are a run that is not running, keeps what it found and waits for Continue.
     * STOPPED is Cancel, or the app closed over a run. OFFLINE is YouTube out of reach after three
     * tries. PAUSED is YouTube refusing the searches, or the app backing off because YouTube is
     * refusing it everything: nothing is asked again until somebody says so.
     */
    enum class Stage { IDLE, READING, FAILED, MATCHING, STOPPED, OFFLINE, PAUSED, MATCHED, CREATING, CREATED }

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
     * nothing more, so a file that finished reading late, or the import kept from last time,
     * cannot take the place of the file chosen after it.
     */
    private var round = 0
    private var job: Job? = null

    @Volatile
    private var run: ImportRun? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Everything that touches the kept import's files, one piece of work at a time and in the
     * order it was asked for. A save asked for before Start over must not land after it and bring
     * the import back, and no two writes may be at the same file.
     */
    private val disk = Channel<() -> Unit>(Channel.UNLIMITED)

    /** Whether a save is already waiting out [saveAfter], so that the changes until then join it. */
    private val saveWaiting = AtomicBoolean(false)

    init {
        scope.launch {
            for (work in disk) {
                try {
                    work()
                } catch (e: Exception) {
                    // The import goes on in memory. It is only a restart that would lose it.
                    Log.w(TAG, "Could not write the import down", e)
                }
            }
        }
        // An import the app was closed over. Its files are read off the main thread, and until
        // they are the screen says it is reading, which it is, in place of offering a file.
        if (runCatching { store.holdsOne() }.getOrDefault(false)) {
            synchronized(lock) {
                _state.value = State(stage = Stage.READING)
                val mine = round
                job = scope.launch { restore(mine) }
            }
        }
    }

    private suspend fun restore(mine: Int) {
        val kept = try {
            // Not once a file has been chosen meanwhile: read after that file had begun to be
            // kept, this would come back with that file's own import and take its place.
            fromDisk { if (synchronized(lock) { mine == round }) store.load() else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the kept import back", e)
            null
        }
        synchronized(lock) {
            if (mine != round) return
            if (kept == null) {
                Log.w(TAG, "An import was kept and could not be read back: starting from nothing")
                _state.value = State()
                return
            }
            if (kept.lostBlocks > 0) Log.w(TAG, "${kept.lostBlocks} blocks of the kept import could not be read: their songs are looked up again")
            run = kept.run
            _state.value = State(
                // Stopped, whatever it was doing when the app closed. Nothing is asked of YouTube
                // because the app was opened: somebody says Continue first.
                stage = if (kept.run.complete()) Stage.MATCHED else Stage.STOPPED,
                fileName = kept.fileName,
                format = kept.format,
                playlists = kept.run.playlists.size,
                skippedRows = kept.skippedRows,
                progress = kept.run.snapshot(),
            )
        }
    }

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
                    // Before the first lookup, so that an app closed a second from now still
                    // comes back with the file.
                    onDisk { store.begin(name, parsed.format, parsed.skippedRows, run) }
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
        // Kept, and written down now: the screen says so, and the next thing may be the app closing.
        saveNow()
    }

    /** Forgets the file and everything matched from it, here and on disk. Nothing already created is touched. */
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
        saveSoon()
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
                    // The import is over, and only now: an app closed while the playlists were
                    // being written comes back with the import as it was, to create them again.
                    onDisk { store.end(run) }
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
                saveSoon()
            }
            setStage(Stage.MATCHED, run)
        } catch (e: SearchRefused) {
            // A run that was cancelled as the refusal came stays as Cancel left it.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Paused matching: YouTube refused a search, or the app is backing off", ErrorText.forLog(e))
            setStage(Stage.PAUSED, run)
        } catch (e: SearchUnavailable) {
            // A run that was cancelled while its last try was failing stays as Cancel left it.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Stopped matching: YouTube could not be reached", ErrorText.forLog(e))
            setStage(Stage.OFFLINE, run)
        }
        // However it ended, nothing more comes by itself, so nothing is left waiting to be written.
        saveNow()
    }

    /**
     * Writes down what has changed, a little later and all at once. A lookup lands about twice a
     * second and a tap on the review screen as fast as a thumb moves, and a file written for each
     * of them would be writing the same block over and over.
     */
    private fun saveSoon() {
        if (!saveWaiting.compareAndSet(false, true)) return
        scope.launch {
            saveAfter()
            // Before the save is asked for and not after: a change that finds this still set has
            // to be one the save below is certain to see.
            saveWaiting.set(false)
            saveNow()
        }
    }

    /**
     * Writes down what has changed without waiting out the seconds a change otherwise waits. The
     * screen asks for it as it goes out of sight: a pick made a moment before the app is swiped
     * away is a pick somebody made.
     */
    fun keep() = saveNow()

    /** Writes down what has changed without waiting, for the moments after which nothing may follow. */
    private fun saveNow() {
        val run = run ?: return
        onDisk { store.save(run) }
    }

    private fun onDisk(work: () -> Unit) {
        disk.trySend(work)
    }

    /** [work] in its turn among the rest, and what it came to. */
    private suspend fun <T> fromDisk(work: () -> T): T {
        val answer = CompletableDeferred<T>()
        onDisk {
            try {
                answer.complete(work())
            } catch (e: Throwable) {
                answer.completeExceptionally(e)
            }
        }
        return answer.await()
    }

    /** For a test, which has no other way to know: everything asked of the disk so far is done. */
    internal suspend fun written() = fromDisk { }

    /** For a test: every change made so far is on the disk. */
    internal suspend fun saved() {
        while (saveWaiting.get() || fromDisk { run?.let(store::owes) == true }) delay(1)
    }

    /**
     * Drops the import there is, in memory and on disk, and whatever was working on it, and says
     * [stage]. Under [lock]. The number it gives back is the new round's, for the job that starts it.
     */
    private fun startAgain(stage: Stage): Int {
        round++
        job?.cancel()
        job = null
        run = null
        _state.value = State(stage = stage)
        onDisk { store.clear() }
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
        private val CAN_CONTINUE = setOf(Stage.STOPPED, Stage.OFFLINE, Stage.PAUSED)

        /**
         * The folder under the app's own files where the import under way is kept. The backup
         * rules name it too (res/xml), to keep it out of the cloud backup.
         */
        private const val FOLDER = "library-import"

        /**
         * A change is on the disk five seconds after it was made, at the latest. That is about a
         * dozen lookups to make again if the app is killed, and one small file written every five
         * seconds while a run lasts.
         */
        private const val SAVE_AFTER_MS = 5_000L
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
