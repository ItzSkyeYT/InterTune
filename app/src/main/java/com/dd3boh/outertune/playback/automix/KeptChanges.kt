/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.util.Log
import com.dd3boh.outertune.mix.edit.TransitionJson
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.StoredChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Changes rendered in the editor when they are saved, kept as they were heard (his ask of 8 Nov
 * 2026: "plays the transition from storage instead of having to re-render it"): one file a pair,
 * in the app's files that are not backed up (a 16-bar change is about 15 MB, more than a whole
 * backup may hold). The playlist plays one where the music it holds is the music it was rendered
 * from (StoredChange.replay) and renders the change again otherwise. Blocking: off the main thread.
 */
class KeptChanges(filesDir: File) {
    val dir = File(filesDir, "mix/changes")

    fun file(from: String, to: String) = File(dir, "${digest(from)}-${digest(to)}.bin")

    fun get(from: String, to: String): StoredChange? {
        val f = file(from, to)
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(f.inputStream(), BUFFER)).use { StoredChange.read(it) }
        }.onFailure { Log.w(TAG, "A kept change could not be read", it) }.getOrNull()
    }

    /**
     * Whether the change kept for the pair was rendered for [spec] as written, on parts where [parts]
     * says the songs have them: read from the file's head alone.
     */
    fun holds(from: String, to: String, spec: TransitionSpec, parts: Boolean): Boolean {
        val f = file(from, to)
        if (!f.isFile) return false
        val head = runCatching { DataInputStream(BufferedInputStream(f.inputStream(), 4096)).use { StoredChange.headOf(it) } }.getOrNull()
            ?: return false
        return head.first == written(spec) && (head.second || !parts)
    }

    fun put(from: String, to: String, change: StoredChange): Boolean = synchronized(LOCK) { write(from, to, change) }

    private fun write(from: String, to: String, change: StoredChange): Boolean = runCatching {
        dir.mkdirs()
        val f = file(from, to)
        // Into a file beside it, then renamed over it, so a reader never meets half of one.
        val fresh = File(f.path + ".new")
        DataOutputStream(BufferedOutputStream(fresh.outputStream(), BUFFER)).use { change.write(it) }
        if (!fresh.renameTo(f)) {
            fresh.delete()
            return false
        }
        prune()
        true
    }.onFailure { Log.w(TAG, "A change could not be kept", it) }.getOrDefault(false)

    fun delete(from: String, to: String): Boolean = file(from, to).delete()

    /** The newest [MAX_FILES] kept: the rest are rendered again when they play. */
    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".bin") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "KeptChanges"
        private const val BUFFER = 1 shl 16

        /** One write at a time: Save and the page's opening may both keep the same pair's change. */
        private val LOCK = Any()

        /** About 600 MB at most, were they all 16-bar changes. */
        const val MAX_FILES = 40

        /** The change as it is kept beside its render: what the playlist compares with the change it reads. */
        fun written(spec: TransitionSpec): String = TransitionJson.encode(spec)

        /** Where Save renders the change it keeps: past the editor's page, which Save closes. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun digest(id: String): String =
            MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
