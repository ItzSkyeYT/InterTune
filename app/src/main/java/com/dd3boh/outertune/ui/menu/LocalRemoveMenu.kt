/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.NotSaved
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalRemoval
import androidx.lifecycle.lifecycleScope
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
 * work after it. The words are read with the menu's own context at the tap, in the app's language. The snackbar lives
 * as long as the activity whose host shows it: one made anew (a dark mode or language switch) has a host of its own,
 * and the old one's snackbar never went, so its wait never ended. The removal stands then, as when the snackbar goes.
 */
object LocalRemoveMenu {

    // A write that fails is written to the log, never let through to close the app.
    private val scope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> reportException(e) })
    }

    fun removeSong(context: Context, database: MusicDatabase, snackbar: SnackbarHostState, song: SongEntity, address: String) {
        val app = context.applicationContext
        // A liked song leaves Liked songs too (LocalRemoval.removeSong), and the message says so (his pick of 19 Nov
        // 2026); Undo brings the like back with the song.
        val said = context.getString(if (song.liked) R.string.local_removed_liked else R.string.local_removed)
        val undo = context.getString(R.string.undo)
        val shows = context.activity()?.lifecycleScope ?: scope
        scope.launch {
            val change = saying(app) { LocalRemoval.removeSong(app, database, song, address) } ?: return@launch
            offerUndo(app, database, snackbar, said, undo, change, shows)
        }
    }

    fun excludeFolder(context: Context, database: MusicDatabase, snackbar: SnackbarHostState, folder: String, address: String) {
        val app = context.applicationContext
        val said = context.getString(R.string.local_folder_excluded, LocalRemoval.folderName(folder))
        val undo = context.getString(R.string.undo)
        val shows = context.activity()?.lifecycleScope ?: scope
        scope.launch {
            val change = saying(app) { LocalRemoval.excludeFolder(app, database, folder, address) } ?: return@launch
            offerUndo(app, database, snackbar, said, undo, change, shows)
        }
    }

    private fun offerUndo(
        context: Context,
        database: MusicDatabase,
        snackbar: SnackbarHostState,
        said: String,
        undo: String,
        change: LocalRemoval.Change,
        shows: CoroutineScope,
    ) {
        shows.launch(CoroutineExceptionHandler { _, e -> reportException(e) }) {
            // The last thing done is the one said: a removal right after another does not wait behind its snackbar.
            snackbar.currentSnackbarData?.dismiss()
            // Long, not the Indefinite a snackbar with an action gets by default: it goes by itself.
            val result = snackbar.showSnackbar(message = said, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                // A write: in the scope of its own, never cut short with the activity.
                scope.launch { saying(context) { LocalRemoval.undo(context, database, change) } }
            }
        }
    }

    /** The activity this context is, or wraps, whose lifetime the snackbar has. */
    private tailrec fun Context.activity(): ComponentActivity? = when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
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
