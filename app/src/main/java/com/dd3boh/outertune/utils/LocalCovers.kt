/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.RequiresApi
import java.io.File
import java.util.Locale

/**
 * The cover of a music file on the phone that has no picture of its own: the image beside it in its
 * folder, cover.jpg, folder.jpg and the like, as music players have long read them (his pick for
 * listeners of their own files, 22 Nov 2026: "intertune has a lot of local music users so they need to
 * get an as good experience"). Before this, such a file had the slate placeholder everywhere.
 *
 * Where the folder shows the app its images (Android 12 and older, with READ_EXTERNAL_STORAGE), the
 * app picks the image itself by its name ([best]). From Android 13 the audio permission shows the app
 * no image file, and the system's own thumbnail of the song is asked instead (Android 10 and newer,
 * ContentResolver.loadThumbnail): the media store makes it from the file's picture or its folder's
 * images, and the audio permission alone may ask for it. No new permission either way.
 */
object LocalCovers {
    /** The names a folder's cover goes by, the best first, in any case. */
    val NAMES = listOf("cover", "folder", "front", "albumart", "album")

    /** The kinds of image read, the best first. */
    val EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    /** The best of [names] (the names in a folder) by [NAMES], then [EXTENSIONS]; null if none is a cover's. */
    fun best(names: List<String>): String? = names
        .mapNotNull { name ->
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return@mapNotNull null
            val base = NAMES.indexOf(name.substring(0, dot).lowercase(Locale.ROOT))
            val ext = EXTENSIONS.indexOf(name.substring(dot + 1).lowercase(Locale.ROOT))
            if (base < 0 || ext < 0) null else Triple(name, base, ext)
        }
        .minWithOrNull(compareBy<Triple<String, Int, Int>>({ it.second }, { it.third }).thenBy { it.first })
        ?.first

    /** The cover beside the file at [path], decoded to about [px] on its longer side, or null where there is none. */
    fun of(context: Context, path: String, px: Int): Bitmap? =
        beside(path, px) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) systemThumbnail(context, path, px) else null

    private fun beside(path: String, px: Int): Bitmap? = runCatching {
        val folder = File(path).parentFile ?: return null
        val name = best(folder.list()?.toList().orEmpty()) ?: return null
        decode(File(folder, name), px)
    }.getOrNull()

    /** The system's thumbnail of the song at [path]: its picture, or its folder's (MediaProvider). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun systemThumbnail(context: Context, path: String, px: Int): Bitmap? = runCatching {
        val songs = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val id = context.contentResolver.query(songs, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        context.contentResolver.loadThumbnail(ContentUris.withAppendedId(songs, id), Size(px, px), null)
    }.getOrNull()

    /** [file] decoded at the largest power of two down that keeps its shorter side at [px] or more. */
    private fun decode(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
