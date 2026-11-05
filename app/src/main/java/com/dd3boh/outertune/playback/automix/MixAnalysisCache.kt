/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.util.Log
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.auto.SongEndJson
import java.io.File
import java.security.MessageDigest

/**
 * Mix: what was found of each song's end and opening (SongEnd), one small file each in the app's
 * cache, so a playlist can say what Auto would do between two songs without reading either of
 * them again. Filled when a change is worked out as it plays, and when the editor reads a pair:
 * nothing is read ahead only to fill it. Android may clear it, which costs only a listen.
 * Blocking: call it off the main thread.
 */
class MixAnalysisCache(private val dir: File) {

    private fun file(songId: String, kind: RegionKind): File {
        val key = MessageDigest.getInstance("SHA-1").digest(songId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$key-${kind.name.lowercase()}.json")
    }

    /** The end ([RegionKind.TAIL]) or opening ([RegionKind.HEAD]) of [songId] as last found, or null. */
    fun get(songId: String, kind: RegionKind): SongEnd? {
        val f = file(songId, kind)
        if (!f.isFile) return null
        return runCatching { SongEndJson.decode(f.readText()) }.getOrNull()
    }

    /** Keeps [end] for [songId], in place of what was there; true when it was written. */
    fun put(songId: String, end: SongEnd): Boolean = runCatching {
        dir.mkdirs()
        val f = file(songId, end.kind)
        // Into a file beside it, then renamed over it, so a reader never meets half of one.
        val fresh = File(f.path + ".new")
        fresh.writeText(SongEndJson.encode(end))
        if (!fresh.renameTo(f)) {
            fresh.delete()
            return false
        }
        prune()
        true
    }.onFailure { Log.w(TAG, "could not keep the analysis of $songId", it) }.getOrDefault(false)

    /** Keeps the newest [MAX_FILES]: about a thousand songs' ends, a few MB. */
    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    private companion object {
        const val TAG = "MixAnalysisCache"
        const val MAX_FILES = 2_000
    }
}
