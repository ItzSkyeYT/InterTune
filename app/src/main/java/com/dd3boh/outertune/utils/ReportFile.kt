/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A report shared as a text file, for Report a problem and the crash page: Discord takes a file
 * from anyone, where a message past 2,000 characters wants Nitro and a log pasted in pieces came
 * out garbled (2 Nov 2026). The file goes to whichever app is picked in the share sheet and stays
 * in the app's own cache, nowhere else, for a day.
 */
object ReportFile {
    /** "InterTune-report-2026-11-02-1830.txt": the day and the minute it was shared, in the phone's time. */
    fun name(at: Long): String = "InterTune-report-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(at)) + ".txt"

    /** Writes [text] to a file of its own and opens the share sheet for it. True when the sheet opened. */
    suspend fun share(context: Context, text: String): Boolean = runCatching {
        val now = System.currentTimeMillis()
        val file = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, FOLDER)
            folder.listFiles()?.filter { now - it.lastModified() > KEPT_MS }?.forEach { it.deleteRecursively() }
            // A folder of its own each time, so a second report in the same minute keeps the name
            // and never writes over one an app is still reading.
            val own = File(folder, now.toString()).apply { mkdirs() }
            File(own, name(now)).also { it.writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The share sheet's preview, and the right to read the file for whichever app is picked.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(context.shareSheet(send))
    }.onFailure { Log.w(TAG, "Could not share the report as a file", it) }.isSuccess

    const val FOLDER = "reports"
    private const val KEPT_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "ReportFile"
}
