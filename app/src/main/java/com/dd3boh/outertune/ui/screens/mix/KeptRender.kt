/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.engine.Preview
import com.dd3boh.outertune.mix.engine.StoredChange
import com.dd3boh.outertune.playback.automix.KeptChanges
import com.dd3boh.outertune.playback.automix.MixStores

/**
 * The change saved for [saved] rendered as the editor plays it, from [loaded]'s copies of the two
 * songs at their levels [levelA] and [levelB], and kept (KeptChanges) for the playlist to play
 * without rendering it; the kept one dropped where the change is plain or gone. Blocking. The
 * editor does this on Save, on leaving and on opening a pair whose change is not kept yet; the
 * debug app's trial hook does it from the shell.
 */
internal fun keepRender(context: Context, loaded: LoadedPair, levelA: Double, levelB: Double, saved: SongPair): StoredChange? {
    val kept = KeptChanges(context.noBackupFilesDir)
    val started = System.nanoTime()
    return runCatching {
        val spec = MixStores.transitions(context).get(saved)
        if (spec == null || spec.plain) {
            kept.delete(saved.from, saved.to)
            return null
        }
        // As the page plays it: a change saved on bands on the parts, where both songs have them.
        val heard = if (loaded.partsReady) Presets.onParts(spec) else spec
        val window = Preview.render(heard, loaded.aCopy, loaded.bCopy, loaded.a.analysis, levelA, levelB).getOrThrow()
        val change = StoredChange.of(KeptChanges.written(spec), window.render, loaded.aCopy.pcm, loaded.aCopy.startMs, loaded.bCopy.pcm)
        if (!kept.put(saved.from, saved.to, change)) return null
        Log.i(TAG, "The saved change is kept as rendered, on " + (if (change.onParts) "parts" else "bands") + ", in %.0f ms".format((System.nanoTime() - started) / 1e6))
        change
    }.onFailure {
        kept.delete(saved.from, saved.to)
        Log.w(TAG, "The saved change could not be kept as rendered", it)
    }.getOrNull()
}

private const val TAG = "MixEditor"
