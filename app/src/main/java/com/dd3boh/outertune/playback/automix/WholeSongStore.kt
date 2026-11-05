/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.util.Log
import com.dd3boh.outertune.mix.WholeSongGrid
import java.io.File
import java.security.MessageDigest

/**
 * Mix: each song's beats as read from the whole song once (WholeSongGrid), one small file each, in
 * the app's own files and not its cache: a grid the listener locked must not go when Android
 * clears the cache. A locked grid is never written over by an analysis, and is kept through a
 * change of the analysis's version. Blocking: call it off the main thread.
 */
class WholeSongStore(private val dir: File) {

    private fun file(songId: String): File {
        val key = MessageDigest.getInstance("SHA-1").digest(songId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$key.json")
    }

    /** The grid of [songId], or null where it was never read whole (or was read by another version and not locked). */
    fun get(songId: String): WholeSongGrid? {
        val f = file(songId)
        if (!f.isFile) return null
        return runCatching { WholeSongGrid.decode(f.readText()) }.getOrNull()
    }

    /** Keeps [grid] for [songId], unless a locked one is there; true when it was written. */
    fun put(songId: String, grid: WholeSongGrid): Boolean {
        if (!grid.locked && get(songId)?.locked == true) return false
        return write(songId, grid).also { if (it) prune() }
    }

    /** Locks or unlocks the grid kept for [songId], as it stands; false where there is none. */
    fun lock(songId: String, locked: Boolean): Boolean {
        val g = get(songId) ?: return false
        if (g.locked == locked) return true
        return write(songId, g.withLock(locked))
    }

    private fun write(songId: String, grid: WholeSongGrid): Boolean = runCatching {
        dir.mkdirs()
        val f = file(songId)
        // Into a file beside it, then renamed over it, so a reader never meets half of one.
        val fresh = File(f.path + ".new")
        fresh.writeText(WholeSongGrid.encode(grid))
        if (!fresh.renameTo(f)) {
            fresh.delete()
            return false
        }
        true
    }.onFailure { Log.w(TAG, "could not keep the grid of $songId", it) }.getOrDefault(false)

    /** Keeps the newest [MAX_FILES] grids that are not locked: a few thousand songs, a few MB. Locked ones stay. */
    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { f ->
            val locked = runCatching { WholeSongGrid.decode(f.readText())?.locked == true }.getOrDefault(false)
            if (!locked) f.delete()
        }
    }

    private companion object {
        const val TAG = "WholeSongStore"
        const val MAX_FILES = 3_000
    }
}
