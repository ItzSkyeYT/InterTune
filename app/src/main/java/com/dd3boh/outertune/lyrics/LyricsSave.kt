/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * A lyrics card or video kept on the phone instead of sent: in its shared Pictures or Movies, in a
 * folder of InterTune's own, where the gallery finds it. Android 10 and up take it there without
 * any permission. Older ones would want the storage permission for that, which the app does not
 * ask for, so there the system's own save screen asks where it goes, and it is written where the
 * person picked ([intoPicked]).
 */
object LyricsSave {
    const val FOLDER = "InterTune"
    const val TYPE_PICTURE = "image/png"
    const val TYPE_VIDEO = "video/mp4"

    /**
     * Where it goes on Android 10 and up, as the gallery and the file apps show it: Pictures/InterTune,
     * Movies/InterTune. The two folders are Environment.DIRECTORY_PICTURES and DIRECTORY_MOVIES,
     * written out: those are not constants, and a test reads them as null.
     */
    fun place(video: Boolean): String = (if (video) "Movies" else "Pictures") + "/" + FOLDER

    /** Whether it goes straight into the gallery ([intoGallery]), or the system's save screen asks first. */
    val straight get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Android 10 and up: a new file named [name] in the phone's Pictures or Movies, under [FOLDER],
     * filled by [write]. Other apps see it only once it is whole, and a file that could not be
     * finished is taken away again. True when it was saved.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun intoGallery(context: Context, name: String, video: Boolean, write: (OutputStream) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) TYPE_VIDEO else TYPE_PICTURE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, place(video))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }
            .onFailure { Log.w(TAG, "No room for $name in ${place(video)}", it) }
            .getOrNull() ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("nothing to write it to")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            if (failure is CancellationException) throw failure
            Log.w(TAG, "Could not save $name", failure)
            false
        }
    }

    /** Older Android: [write] into [uri], the file the person picked on the system's save screen. True when it was saved. */
    suspend fun intoPicked(context: Context, uri: Uri, write: (OutputStream) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use(write) ?: error("nothing to write it to")
            true
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            Log.w(TAG, "Could not save into $uri", failure)
            false
        }
    }

    private const val TAG = "LyricsSave"
}
