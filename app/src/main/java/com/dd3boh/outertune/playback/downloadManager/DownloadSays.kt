/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.scanners.absoluteFilePathFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * What the Download action says of where a song goes on the simpler page (DownloadsSimpleLook.C):
 * once the songs are there, "3 songs saved to Music/InterTune", or that they are kept inside the
 * app. Only for downloads asked for by hand, never for the ones a like or a sync starts. Says
 * nothing where the page is today's.
 */
class DownloadSays(private val context: Context) {
    /** The songs asked for by hand whose end has not been said yet. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    init {
        // Read once at the start, so the menus' entries have the look before the Storage page is ever opened.
        DownloadsSimpleLook.of(context)
    }

    /** Download was chosen by hand for the songs [ids]: their end is said once they are there. */
    fun asked(ids: List<String>) {
        if (DownloadsSimpleLook.of(context).saysWhenDone) asked += ids
    }

    /**
     * The downloads [just] are finished, and moved into the folder where there is one: said for
     * the ones asked for by hand, with how many are files in the folder now ([inFolder]) or, when
     * none is, that they are kept inside the app.
     */
    fun whenDone(just: Set<String>, inFolder: (String) -> Boolean) {
        val mine = just.filter { asked.remove(it) }
        if (mine.isEmpty() || !DownloadsSimpleLook.last.saysWhenDone) return
        val there = mine.count(inFolder)
        val name = if (there > 0) folder() else null
        Log.i(TAG, "Said where ${mine.size} downloads went, $there of them in the folder")
        say(
            if (name != null) context.resources.getQuantityString(R.plurals.dl_simple_saved_to, there, there, name)
            else context.resources.getQuantityString(R.plurals.dl_simple_saved_inside, mine.size, mine.size)
        )
    }

    /** The download folder as a person names it, or null when none is saved or it has no path. */
    private fun folder(): String? = runCatching {
        uriListFromString(context.dataStore.get(DownloadPathKey, "")).firstOrNull()?.let { absoluteFilePathFromUri(context, it) }
    }.getOrNull()?.let { DownloadsSimpleLook.folderName(it) }

    private companion object {
        const val TAG = "DownloadSays"
    }

    /** On the main thread, as the wait for Wi-Fi is said: the callers are media3's threads and the move's. */
    private fun say(text: String) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            Toaster.show(context, text, Toast.LENGTH_LONG)
        }
    }
}
