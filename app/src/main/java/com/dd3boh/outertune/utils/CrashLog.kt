/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.os.Build
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
 * person copies it or opens the prefilled GitHub issue themselves, and it goes without the network
 * addresses a connection that failed names: see [ErrorText].
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
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }?.let(::withoutAddresses)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * [report] with the addresses out of its trace. A crash kept by a build from before they were
     * taken out still names them, and the launch after the update is the one that offers it. The
     * lines above the first empty one are the app's own and stay as they are: the version among
     * them, 0.10.9.5 for one, reads as an address.
     */
    internal fun withoutAddresses(report: String): String {
        val trace = report.substringAfter("\n\n", "")
        return report.dropLast(trace.length) + ErrorText.withoutAddresses(trace)
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun report(thread: Thread, throwable: Throwable): String = buildString {
        appendLine("InterTune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("Thread: ${thread.name}")
        appendLine()
        // Not Log.getStackTraceString, which returns nothing at all when an UnknownHostException is
        // anywhere in the chain, so a crash that began with the phone offline came with no trace.
        // Through ErrorText, so that what people are asked to send does not name their address.
        append(ErrorText.of(throwable))
    }
}
