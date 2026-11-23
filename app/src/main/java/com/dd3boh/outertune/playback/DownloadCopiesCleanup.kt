/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.playback.downloadManager.DownloadCopies
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadFolderState
import com.dd3boh.outertune.playback.downloadManager.fatName
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.scanners.fileFromUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How many extra copies the card said when Not now was tapped: it comes back only for more than that. */
val DownloadCopiesPutOffKey = intPreferencesKey("downloadCopiesPutOff")

/** When a look by name, in a folder that lists as empty, last found no copy: not looked again for 12 hours. */
val DownloadCopiesLookedKey = longPreferencesKey("downloadCopiesLookedAt")

/**
 * The extra copies of downloads a name clash left in the download folder (DownloadCopies), looked for
 * once a start, said on a card at the top of Library > Downloaded songs (DownloadCopiesNote) with how
 * many there are and the room they take, and removed only when the person taps Remove there
 * (Unreleased.DOWNLOAD_COPIES_CLEANUP).
 *
 * Found in the main download folder alone (never an extra folder, never a folder inside it), by a
 * listing of it, or where it lists as empty, as dvd's did, by asking for each song's files by name:
 * its download "<title> [<id>].mka" and its copies, for every song the database has as liked or
 * downloaded, and the file the database has for it. Under the scan's [lock]: a scan, a move into the
 * folder and this never work in the folder at the same time.
 *
 * Removing is safe to stop anywhere, the app ended included. For each song, the file it keeps is
 * looked at first and must still be there, whole, under the name it was found with, or nothing of
 * that song is touched; it is never deleted; each copy is looked at again and deleted only if it is
 * still the file found; the rename to the download's name comes after the copies have gone; then the
 * database points at the file kept. A song the player has open waits until it is let go, a few
 * minutes at most, or for a later time. [record] records a song's file kept as its download, the
 * download map with it (DownloadUtil.recordKeptCopy).
 */
class DownloadCopiesCleanup(
    private val context: Context,
    private val database: MusicDatabase,
    private val folder: () -> DownloadDirectoryManagerOt,
    private val lock: Mutex,
    private val isOpen: (String) -> Boolean,
    private val record: (id: String, path: String) -> Unit,
) {
    sealed interface State {
        /** Nothing to say: none found, put off, or not looked for yet. */
        data object None : State

        /** [files] extra copies found, taking [bytes]: the card is up. */
        data class Found(val files: Int, val bytes: Long) : State

        /** Being removed, [done] of [total]: the card's progress line. */
        data class Removing(val done: Int, val total: Int) : State
    }

    /** How a removal ended: the copies that went and the room they took. */
    data class Removed(val files: Int, val bytes: Long)

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the last look found, with what the database had of each song then, for the removal. */
    private class Picture(val plan: DownloadCopies.Plan, val recorded: Map<String, String?>, val folderPath: String?)

    @Volatile
    private var picture: Picture? = null

    /** Looks for the copies, off the main thread, and puts the card up when there are some, unless they were put off. */
    suspend fun find() {
        if (!Unreleased.DOWNLOAD_COPIES_CLEANUP || _state.value is State.Removing) return
        val found = withContext(Dispatchers.IO) { lock.withLock { look() } } ?: return
        if (_state.value is State.Removing) return
        picture = found
        val files = found.plan.files
        val putOff = context.dataStore.get(DownloadCopiesPutOffKey, 0)
        _state.value = if (files > putOff) State.Found(files, found.plan.bytes) else State.None
        Log.i(TAG, "${found.plan.songs.size} songs have $files extra copies in the download folder, ${found.plan.bytes} bytes" + if (files in 1..putOff) ", put off" else "")
    }

    private suspend fun look(): Picture? {
        val folder = folder()
        if (folder.folderState.value != DownloadFolderState.OPEN) return null
        val folderPath = folder.mainFolderPath()
        val songs = (database.downloadedOrQueuedSongs().first() + database.likedSongsByRowIdAsc().first())
            .filterNot { it.song.isLocal }
            .distinctBy { it.id }
        val recorded = songs.associate { it.id to it.song.localPath }
        // The name of each song's file in this folder, as the database has it.
        val names = recorded.mapNotNull { (id, path) ->
            path?.takeIf { folderPath != null && it.substringBeforeLast('/') == folderPath }?.let { id to it.substringAfterLast('/') }
        }.toMap()
        val listed = folder.ourFilesListed()
        val files = when {
            listed != null && listed.listed > 0 -> listed.ours
            !folder.canLookUp() -> emptyList()
            else -> {
                // The folder lists as empty: each song's files are asked for by name, which takes a
                // few seconds for a few hundred songs. Not again for 12 hours once it found nothing
                // the card would say (none, or no more than were put off).
                val now = System.currentTimeMillis()
                val last = context.dataStore.get(DownloadCopiesLookedKey, 0L)
                if (now - last in 0 until LOOK_AGAIN_MS) return null
                val bases = songs.map { fatName("${it.song.title} [${it.id}]") } + names.values.mapNotNull { DownloadCopies.parse(it)?.base }
                DownloadCopies.lookFor(bases, names.values, folder::lookUp).also { found ->
                    val quiet = DownloadCopies.plan(found, names).files <= context.dataStore.get(DownloadCopiesPutOffKey, 0)
                    runCatching { context.dataStore.edit { it[DownloadCopiesLookedKey] = if (quiet) now else 0L } }
                    Log.i(TAG, "The download folder lists as empty: ${songs.size} songs' files asked for by name, ${found.size} found")
                }
            }
        }
        return Picture(DownloadCopies.plan(files, names), recorded, folderPath)
    }

    /** The card's Not now: down until more copies than these are found. */
    fun notNow() {
        val shown = _state.value as? State.Found ?: return
        _state.value = State.None
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { context.dataStore.edit { it[DownloadCopiesPutOffKey] = shown.files } }
        }
    }

    /** The card's Remove: the copies found go, with the card's progress line. Null when there was nothing to remove. */
    suspend fun remove(): Removed? {
        val found = picture ?: return null
        if (_state.value is State.Removing) return null
        // Another folder chosen since they were found: these were that folder's, and nothing goes.
        if (withContext(Dispatchers.IO) { folder().mainFolderPath() } != found.folderPath) {
            picture = null
            _state.value = State.None
            return null
        }
        val total = found.plan.files
        var done = 0
        var files = 0
        var bytes = 0L
        val step = {
            done++
            _state.value = State.Removing(done, total)
        }
        _state.value = State.Removing(0, total)
        try {
            var waiting = found.plan.songs
            val until = System.currentTimeMillis() + OPEN_WAIT_MS
            while (true) {
                // Never a song the player has open: its file would go or change name under it.
                val (now, later) = waiting.partition { !isOpen(it.id) }
                for (song in now) {
                    val gone = withContext(Dispatchers.IO) { lock.withLock { removeFrom(song, found, step) } }
                    files += gone.files
                    bytes += gone.bytes
                }
                waiting = later
                if (waiting.isEmpty() || System.currentTimeMillis() >= until) break
                delay(OPEN_POLL_MS)
            }
            if (waiting.isNotEmpty()) Log.i(TAG, "${waiting.size} songs played all along: their copies stay until the next time")
        } finally {
            picture = null
            _state.value = State.None
        }
        Log.i(TAG, "Removed $files extra copies, $bytes bytes")
        return Removed(files, bytes)
    }

    /** One song's copies gone, its file kept, named as its download and recorded: see the class. */
    private fun removeFrom(song: DownloadCopies.Song, found: Picture, step: () -> Unit): Removed {
        val folder = folder()
        // The file kept first: still there, whole and the file found, or nothing of this song is touched.
        val kept = folder.lookAt(song.keep.key)
        if (kept == null || kept.name != song.keep.name || kept.size == 0L) {
            Log.w(TAG, "${song.id}: ${song.keep.name} is not there as it was found, none of its files is touched")
            song.remove.forEach { _ -> step() }
            return Removed(0, 0)
        }
        var files = 0
        var bytes = 0L
        var allGone = true
        for (copy in song.remove) {
            val now = folder.lookAt(copy.key)
            when {
                // Gone already.
                now == null -> Unit
                // Not the file found any more: left as it is.
                now.name != copy.name || DownloadCopies.parse(now.name)?.id != song.id -> allGone = false
                folder.removeCopy(copy.key) -> {
                    files++
                    bytes += now.size ?: 0L
                }
                else -> allGone = false
            }
            step()
        }
        // A copy kept takes its download's name once that name is free, so that other players see it again.
        val name = song.renameTo?.takeIf { allGone && folder.canLookUp() && folder.lookUp(it) == null }
        val uri = folder.keepFile(song.id, song.keep.key, name)
        val path = runCatching { fileFromUri(context, uri)?.absolutePath }.getOrNull()
        // The database ends pointing at the file kept (DownloadCopies.repoints), for a song it has.
        if (path != null && song.id in found.recorded && DownloadCopies.repoints(song.id, found.recorded[song.id], path, found.folderPath)) {
            record(song.id, path)
        }
        return Removed(files, bytes)
    }

    companion object {
        private const val TAG = "DownloadCopies"
        private const val LOOK_AGAIN_MS = 12 * 60 * 60 * 1000L

        /** How long a removal waits for the player to let go of a song with copies, and how often it asks. */
        private const val OPEN_WAIT_MS = 3 * 60_000L
        private const val OPEN_POLL_MS = 2_000L
    }
}
