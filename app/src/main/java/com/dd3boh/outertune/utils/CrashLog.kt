/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so the next launch can offer to report it.
 *
 * InterTune has no crash reporting service and never will, so a crash on a device nobody here owns
 * used to be invisible: the person saw "InterTune keeps stopping" and gave up. Now the stack trace
 * is written to a file in the app's own storage and nothing else. It leaves the phone only if the
 * person copies it or opens the prefilled GitHub issue themselves.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    /** Chains in front of whatever handler is already installed, which still runs afterwards. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Nothing in here may throw: this runs while the process is already going down.
            runCatching { file(appContext).writeText(report(thread, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? = runCatching {
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun report(thread: Thread, throwable: Throwable): String = buildString {
        appendLine("InterTune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("Thread: ${thread.name}")
        appendLine()
        append(Log.getStackTraceString(throwable))
    }
}
