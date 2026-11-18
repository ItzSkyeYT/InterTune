/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.Toaster

/**
 * Open file location (#150): a local song's menu opens the folder the file is in, in the phone's
 * file manager. Android has no "show this file" intent, so the folder is asked for as a document of
 * the storage provider, which Files by Google and the system's own file app both answer.
 */
object OpenFileLocation {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private val internal = Regex("""^/(?:storage/emulated|sdcard|storage/self)/(?:\d+/)?(.*)$""")
    private val card = Regex("""^/storage/([^/]+)/(.*)$""")

    /** The folder's document id ("primary:Music/Rock", "1234-ABCD:Music") for a file's path, or null when the path is not on shared storage. */
    fun folderDocumentId(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val (volume, inside) = when {
            internal.matches(path) -> "primary" to internal.find(path)!!.groupValues[1]
            card.matches(path) -> card.find(path)!!.let { it.groupValues[1] to it.groupValues[2] }
            else -> return null
        }
        val folder = inside.substringBeforeLast('/', missingDelimiterValue = "")
        return "$volume:$folder"
    }

    fun open(context: Context, path: String?) {
        val id = folderDocumentId(path)
        val shown = id != null && runCatching {
            val uri = DocumentsContract.buildDocumentUri(AUTHORITY, id)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!shown) Toaster.show(context, R.string.open_file_location_failed)
    }
}
