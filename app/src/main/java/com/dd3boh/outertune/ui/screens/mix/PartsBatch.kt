/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.dd3boh.outertune.constants.Unreleased
import java.io.File
import java.io.IOException

/**
 * A playlist's parts made in one sitting (his ask of 9 Nov 2026: "an option to split the parts
 * (stems) of a whole playlist in one sitting, using all threads/CPU"): both ends of each song, the
 * windows the editor and a mix take parts from, read and split exactly as the editor does
 * ([PairLoader.window], [PartsMaker]), several at once where the phone has the cores and the
 * memory, below the music. Windows already split are passed over. The debug app's service does
 * the work (PartsBatchService, in its own source set beside the model's runtime); this holds what
 * can be decided without it.
 */
object PartsBatch {
    private const val TAG = "PartsBatch"

    /** The debug app's service, found by name: only that build has it. */
    const val SERVICE = "com.dd3boh.outertune.playback.parts.PartsBatchService"

    /** Whether this build offers it: the debug app, as the rest of the parts. */
    val offered: Boolean get() = Unreleased.AUTOMIX_PARTS

    /** A song to split: its id, its title, and its length in ms where known (below 0 where not). */
    data class Song(val id: String, val title: String, val durationMs: Double)

    /** One window to make: [song]'s end ([PairLoader.TAIL]) or opening ([PairLoader.HEAD]). */
    data class Window(val song: Song, val end: String)

    /** Both windows of each song, once, in the playlist's order: in a mix any song may be the one ending or the one coming in. */
    fun windows(songs: List<Song>): List<Window> =
        songs.distinctBy { it.id }.flatMap { listOf(Window(it, PairLoader.TAIL), Window(it, PairLoader.HEAD)) }

    /** Whether [w] is read and split already: its window kept whole, and its four parts as long. */
    internal fun done(cache: EditorCache, store: PartsStore, w: Window): Boolean {
        val (pcm, info, _) = cache.files(w.song.id, w.end)
        val known = info.takeIf { it.isFile }?.let { runCatching { WindowInfo.of(it.readText()) }.getOrNull() } ?: return false
        val samples = known.frames * known.channels
        return pcm.length() == samples * 2 && store.has(w.song.id, w.end, samples)
    }

    /** How many of [songs] have both their windows in [done]. */
    fun songsDone(songs: List<Song>, done: Set<Window>): Int =
        songs.distinctBy { it.id }.count { s -> Window(s, PairLoader.TAIL) in done && Window(s, PairLoader.HEAD) in done }

    /** How many windows are split side by side, and the runtime's threads in each. */
    data class Jobs(val count: Int, val threads: Int)

    /**
     * As many splits at once as the memory holds, each [perSplit] at its peak, with [reserve] kept
     * for the music, the rest of the phone and an opening of the editor; at most one for each
     * [MIN_THREADS] of the [cores], one for each window [left] and [MAX_JOBS]; at least one. The
     * cores are shared out between them, never more than [MAX_THREADS] to a split, past which the
     * runtime gains nothing (PARTS-NOW.md).
     */
    fun jobs(cores: Int, available: Long, left: Int, perSplit: Long = PER_SPLIT, reserve: Long = RESERVE): Jobs {
        val byMemory = ((available - reserve) / perSplit).toInt()
        val count = minOf(byMemory, cores / MIN_THREADS, left, MAX_JOBS).coerceAtLeast(1)
        return Jobs(count, (cores / count).coerceIn(1, MAX_THREADS))
    }

    /** What a window and its four parts weigh before it is read: the editor's reach of it at 48 kHz in stereo. */
    fun weight(end: String): Long =
        5L * 4 * (48_000 * (if (end == PairLoader.TAIL) PairFacts.REACH_A_MS else PairFacts.REACH_B_MS) / 1000).toLong()

    /**
     * The first windows of [todo] that fit in [room] bytes: past it, each new one would push out the
     * oldest windows kept with their parts, the first of the same playlist among them (EditorCache.prune).
     */
    fun fitting(todo: List<Window>, room: Long): List<Window> {
        var left = room
        val out = ArrayList<Window>()
        for (w in todo) {
            left -= weight(w.end)
            if (left < 0) break
            out += w
        }
        return out
    }

    /** The songs waiting to be split, one a line, kept where a restart of the app finds them. */
    class Queue(private val file: File) {
        fun read(): List<Song> = synchronized(LOCK) {
            runCatching {
                if (!file.isFile) return emptyList()
                file.readLines().mapNotNull { line ->
                    val cells = line.split('\t', limit = 3)
                    if (cells.size < 3 || cells[0].isEmpty()) null else Song(cells[0], cells[2], cells[1].toDoubleOrNull() ?: -1.0)
                }
            }.onFailure { Log.w(TAG, "The queue could not be read", it) }.getOrDefault(emptyList())
        }

        /** [songs] after those waiting, each once. */
        fun add(songs: List<Song>) = synchronized(LOCK) { write((read() + songs).distinctBy { it.id }) }

        /** Takes the songs of [ids] out, as they are done. */
        fun remove(ids: Set<String>) = synchronized(LOCK) {
            val left = read().filterNot { it.id in ids }
            if (left.isEmpty()) file.delete() else write(left)
        }

        fun clear() = synchronized(LOCK) { file.delete() }

        private fun write(songs: List<Song>) {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeText(songs.joinToString("") { "${it.id}\t${it.durationMs}\t${it.title.replace('\n', ' ').replace('\t', ' ')}\n" })
            if (!part.renameTo(file)) throw IOException("could not keep ${file.name}")
        }

        companion object {
            private val LOCK = Any()

            fun of(context: Context) = Queue(File(context.noBackupFilesDir, "mix/parts-queue.tsv"))
        }
    }

    /**
     * Puts [songs] in the queue and starts the debug app's service on it; false where this build has
     * none. Where the phone has no model, the service says so on its notification. [jobs] above 0
     * sets the splits at once in place of [jobs]' own count (the debug hook's, for timing).
     */
    fun start(context: Context, songs: List<Song>, jobs: Int = 0): Boolean {
        if (!offered || songs.isEmpty()) return false
        return runCatching {
            Queue.of(context).add(songs)
            val intent = Intent().setClassName(context, SERVICE)
            if (jobs > 0) intent.putExtra(EXTRA_JOBS, jobs)
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { Log.w(TAG, "The playlist's parts could not be started", it) }.isSuccess
    }

    /** The service's word for [start]'s jobs. */
    const val EXTRA_JOBS = "jobs"

    const val MIN_THREADS = 2
    const val MAX_THREADS = 4
    const val MAX_JOBS = 4

    /** A split's peak memory: the runtime's arena off, 1.24 GB for the whole app at its peak on the S25 Ultra (19 Oct). */
    const val PER_SPLIT = 1_100L shl 20

    /** Kept free for the music, the system and one opening of the editor, which splits a window of its own. */
    const val RESERVE = 2_000L shl 20
}
