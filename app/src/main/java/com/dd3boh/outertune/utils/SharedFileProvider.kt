/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

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
 *
 * The picture goes only to a reader that asks for a picture and would not take the video itself
 * ([SharedStill.wanted]). It used to go to any reader whose type filter took a JPEG, the filter
 * for any type included, so an app that reads a shared file as any type (Discord) got the picture under the
 * video's name and showed a video that played nothing (his report of 1 Nov 2026).
 */
class SharedFileProvider : FileProvider() {

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        // The share sheet looks for a picture among these to show one; a reader that takes the first
        // gets the video. The still is only offered where it is there.
        val hasStill = still(uri)?.let { it.close(); true } ?: false
        return SharedStill.streamTypes(getType(uri), mimeTypeFilter, hasStill) ?: super.getStreamTypes(uri, mimeTypeFilter)
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        if (SharedStill.wanted(getType(uri), mimeTypeFilter)) {
            still(uri)?.let { return AssetFileDescriptor(it, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
        }
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts)
    }

    /** The picture saved beside the video at [uri], open to read, or null where there is none. */
    private fun still(uri: Uri): ParcelFileDescriptor? = runCatching { openFile(Uri.parse("$uri$STILL_SUFFIX"), "r") }.getOrNull()

    companion object {
        const val STILL_SUFFIX = ".jpg"
    }
}

/** When a shared video's picture is handed out instead of the video: plain, so a test can ask. */
internal object SharedStill {
    const val TYPE = "image/jpeg"

    /**
     * Whether a reader asking with [filter] for a file of [type] gets the picture saved beside it:
     * only for a video, only when the filter takes a JPEG, and only when it would not take the
     * video itself. A filter for pictures (the share sheet's preview) gets the picture; one for any
     * type or for videos gets the video.
     */
    fun wanted(type: String?, filter: String): Boolean =
        type != null && type.startsWith("video/") && matches(TYPE, filter) && !matches(type, filter)

    /**
     * What a video of [type] can be read as, for a reader asking with [filter]: the video itself first,
     * then its picture when [hasStill]. Android's share sheet shows a picture of what is sent only when
     * an image type is among them (asked for any type); a reader that takes the first gets the video.
     * Null for anything that is not a video: the provider's own answer stands.
     */
    fun streamTypes(type: String?, filter: String, hasStill: Boolean): Array<String>? {
        if (type == null || !type.startsWith("video/")) return null
        val types = buildList {
            if (matches(type, filter)) add(type)
            if (hasStill && matches(TYPE, filter)) add(TYPE)
        }
        return types.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /** Whether the concrete type [type] fits [filter] (any type, all of a kind, or one exact type), as ClipDescription.compareMimeTypes has it. */
    fun matches(type: String, filter: String): Boolean {
        val (kind, sub) = type.lowercase().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (wantKind, wantSub) = filter.lowercase().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (wantKind == "*") return wantSub == "*" || wantSub == sub
        if (wantKind != kind) return false
        return wantSub == "*" || wantSub == sub
    }
}
