/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.R
import kotlin.coroutines.cancellation.CancellationException

/**
 * A write the phone refused, said in a toast instead of closing the app. With the storage full,
 * DataStore and the database fail every write, and thrown in a scope with no handler the exception
 * ended the app at a tap on any switch, sort or Add to playlist (UI scan, 5 Nov 2026).
 */
object NotSaved {
    private const val TAG = "NotSaved"

    /** Whether [e], or what caused it, is a write refused for want of space. */
    fun isStorageFull(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
        it is SQLiteFullException || it.message?.let { m -> "ENOSPC" in m || "No space left" in m } == true
    }

    /** Says that a write failed, and why when it can tell. From any thread. */
    fun say(context: Context, e: Throwable) {
        Log.w(TAG, "A write was refused", e)
        FailureLog.record("Saving a change", e)
        val app = context.applicationContext ?: context
        val full = isStorageFull(e)
        Handler(Looper.getMainLooper()).post {
            Toaster.show(app, if (full) R.string.not_saved_storage_full else R.string.not_saved, Toast.LENGTH_LONG)
        }
    }
}

/** A settings write that says so when it fails, rather than closing the app: see [NotSaved]. */
suspend fun Context.editOrSay(transform: suspend (MutablePreferences) -> Unit) {
    try {
        dataStore.edit(transform)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NotSaved.say(this, e)
    }
}
