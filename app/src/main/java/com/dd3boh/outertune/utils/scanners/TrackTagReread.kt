/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.TrackTagsRereadKey
import com.dd3boh.outertune.db.LocalSongFile
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.ui.utils.clearDtCache
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scannerState
import com.kyant.taglib.TagLib
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the track and disc tags again, once, for the local songs that were scanned without a
 * track number.
 *
 * Up to 0.12.1 a tag that also holds the count, "4/12", gave no number (issue #39), and the
 * MediaStore scanner stored none on Android 10 and older. A scan never reads a file it already
 * knows again unless a full rescan is asked for, so those songs would have kept no number. This
 * runs in the background after launch, shows nothing, and once it has been through them it never
 * runs again.
 *
 * Light: only songs with no track number, a page of them per query, one file at a time, and only
 * the tags (no audio properties, no pictures). Safe if interrupted: a song's numbers are saved as
 * soon as its file is read, and the id of the song about to be read is written down first, so the
 * next launch carries on after it. A file that takes the app down while it is read is passed over
 * then, instead of taking it down at every launch.
 */
object TrackTagReread {
    private const val TAG = "TrackTagReread"

    const val PAGE = 50
    private const val PAUSE_MS = 100L

    /** Launch has enough to do: the pass starts a little after it. */
    private const val START_AFTER_MS = 5_000L

    /** Holds the id of the song the pass began to read last. In noBackupFilesDir: it is this phone's. */
    private const val MARK_FILE = "track-tag-reread"

    /** Where the pass is, kept across launches. */
    interface Marks {
        /** The id of the song the pass began to read last, or null when it has not begun. */
        fun at(): String?
        fun setAt(id: String)
        suspend fun done(): Boolean
        suspend fun setDone()
    }

    /**
     * Runs the pass, or what is left of it, and returns how many songs got a track number.
     *
     * [canRead] is false while the files cannot be read (local media off, no permission to read
     * them): nothing is read then, and the pass waits for a launch where they can be, unless no
     * song needs it, which ends it at once. [page] gives the songs with no track number after an
     * id, in id order. [read] gives a file's (track, disc), or null when it cannot be read. [save]
     * stores what was found.
     */
    suspend fun run(
        marks: Marks,
        canRead: Boolean,
        page: (afterId: String, limit: Int) -> List<LocalSongFile>,
        read: (path: String) -> Pair<Int?, Int?>?,
        save: (id: String, track: Int?, disc: Int?) -> Unit,
        pauseMs: Long = PAUSE_MS,
    ): Int {
        if (marks.done()) return 0
        var after = marks.at() ?: ""
        if (!canRead) {
            if (page(after, 1).isEmpty()) marks.setDone()
            return 0
        }
        var numbered = 0
        while (true) {
            val songs = page(after, PAGE)
            if (songs.isEmpty()) break
            for (song in songs) {
                currentCoroutineContext().ensureActive()
                marks.setAt(song.id)
                after = song.id
                val (track, disc) = read(song.localPath) ?: continue
                if (track == null && disc == null) continue
                save(song.id, track, disc)
                if (track != null) numbered++
            }
            delay(pauseMs)
        }
        marks.setDone()
        return numbered
    }

    /** One pass at a time, whatever starts it. */
    private val running = AtomicBoolean(false)

    /** The pass as the app runs it, after the scan at launch. */
    suspend fun runOnce(context: Context, database: MusicDatabase) {
        if (!running.compareAndSet(false, true)) return
        try {
            val marks = marks(context)
            if (marks.done()) return
            delay(START_AFTER_MS)
            // A scan writes the same rows. The next launch, then.
            if (scannerState.value > 0) return
            val canRead = context.dataStore.get(LocalLibraryEnableKey, true) &&
                    context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) == PackageManager.PERMISSION_GRANTED
            val numbered = run(
                marks = marks,
                canRead = canRead,
                page = { after, limit -> database.localSongsWithoutTrackNumber(after, limit) },
                read = ::readTags,
                save = { id, track, disc -> database.setTrackTags(id, track, disc) },
            )
            // The Folders tab builds its folders from this cache, and would show the old numbers.
            if (numbered > 0) clearDtCache()
            if (marks.done()) Log.i(TAG, "Done: $numbered local songs got their track number")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
        } finally {
            running.set(false)
        }
    }

    private fun marks(context: Context) = object : Marks {
        private val file = File(context.noBackupFilesDir, MARK_FILE)

        override fun at(): String? = runCatching { file.readText().trim() }.getOrNull()?.ifEmpty { null }

        override fun setAt(id: String) {
            runCatching { file.writeText(id) }
        }

        override suspend fun done(): Boolean = context.dataStore.get(TrackTagsRereadKey, false)

        override suspend fun setDone() {
            context.dataStore.edit { it[TrackTagsRereadKey] = true }
            file.delete()
        }
    }

    /** A file's (track, disc) as TagLibScanner reads them, from its tags alone. Null when it cannot be read. */
    private fun readTags(path: String): Pair<Int?, Int?>? = try {
        val file = File(path)
        if (!file.isFile) {
            null
        } else {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)?.let { TrackTags.fromPropertyMap(it.propertyMap) }
            }
        }
    } catch (e: Exception) {
        null
    }
}
