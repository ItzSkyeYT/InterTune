/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.NotSaved
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalRemoval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remove from library on a file of the phone's and Exclude folder on a folder's row (LocalRemoval): the work off the
 * main thread, then what it did said in the snackbar every screen shows, with Undo, as the pictures he picked have
 * it (experiments/mocks-1115/1-local-songs, A-2 and A-4).
 *
 * In a scope of its own, not the menu's: the menu sheet has closed by the time the snackbar shows, and Undo has to
 * work after it. The words are read with the menu's own context at the tap, in the app's language.
 */
object LocalRemoveMenu {

    // A write that fails is written to the log, never let through to close the app.
    private val scope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> reportException(e) })
    }

    fun removeSong(context: Context, database: MusicDatabase, snackbar: SnackbarHostState, song: SongEntity, address: String) {
        val app = context.applicationContext
        val said = context.getString(R.string.local_removed)
        val undo = context.getString(R.string.undo)
        scope.launch {
            val change = saying(app) { LocalRemoval.removeSong(app, database, song, address) } ?: return@launch
            offerUndo(app, database, snackbar, said, undo, change)
        }
    }

    fun excludeFolder(context: Context, database: MusicDatabase, snackbar: SnackbarHostState, folder: String, address: String) {
        val app = context.applicationContext
        val said = context.getString(R.string.local_folder_excluded, LocalRemoval.folderName(folder))
        val undo = context.getString(R.string.undo)
        scope.launch {
            val change = saying(app) { LocalRemoval.excludeFolder(app, database, folder, address) } ?: return@launch
            offerUndo(app, database, snackbar, said, undo, change)
        }
    }

    private suspend fun offerUndo(
        context: Context,
        database: MusicDatabase,
        snackbar: SnackbarHostState,
        said: String,
        undo: String,
        change: LocalRemoval.Change,
    ) {
        // The last thing done is the one said: a removal right after another does not wait behind its snackbar.
        snackbar.currentSnackbarData?.dismiss()
        // Long, not the Indefinite a snackbar with an action gets by default: it goes by itself.
        val result = snackbar.showSnackbar(message = said, actionLabel = undo, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            saying(context) { LocalRemoval.undo(context, database, change) }
        }
    }

    /** [work] off the main thread; a write the phone refused is said in the app's own words (NotSaved), and null. */
    private suspend fun <T> saying(context: Context, work: suspend () -> T): T? = try {
        withContext(Dispatchers.IO) { work() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NotSaved.say(context, e)
        null
    }
}
