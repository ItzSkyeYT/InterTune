/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.ClipDescription
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * The app's FileProvider, with one thing more: a video it hands out can be seen as a picture, the
 * one saved beside it as "<the video's name>.jpg" (a lyrics video's first frame, LyricsVideo).
 * The share sheet asks for that picture to show what is being sent, and without it showed a
 * video as a plain file. Everything else is FileProvider's own.
 */
class SharedFileProvider : FileProvider() {

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        if (isVideo(uri) && ClipDescription.compareMimeTypes(STILL_TYPE, mimeTypeFilter)) {
            still(uri)?.let {
                it.close()
                return arrayOf(STILL_TYPE)
            }
        }
        return super.getStreamTypes(uri, mimeTypeFilter)
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        if (isVideo(uri) && ClipDescription.compareMimeTypes(STILL_TYPE, mimeTypeFilter)) {
            still(uri)?.let { return AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
        }
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts)
    }

    private fun isVideo(uri: Uri) = getType(uri)?.startsWith("video/") == true

    /** The picture saved beside the video at [uri], open to read, or null where there is none. */
    private fun still(uri: Uri): ParcelFileDescriptor? = runCatching { openFile(Uri.parse("$uri$STILL_SUFFIX"), "r") }.getOrNull()

    companion object {
        const val STILL_SUFFIX = ".jpg"
        private const val STILL_TYPE = "image/jpeg"
    }
}
