/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log

/**
 * A failure the person was told about, written where Report a problem reads it.
 *
 * "The video could not be made" came with two reports that did not hold the error (a listener,
 * 10 Nov 2026): the reason had been written as a warning under tags the report left out, and a
 * toast said the rest. A report is the log's last fault, found by an error line under one of the
 * app's own tags ([ProblemLog.beginsFault]), so a catch that only shows a toast, or warns and goes
 * on, leaves a report that says "Nothing that looks like a fault".
 *
 * One line, one tag, at the error level: what was being done, the exception's class and message
 * and its root cause, and the first of the app's own frames the failure passed through, so the
 * line says where without the page of a trace. It is written next to the toast, not instead of the
 * code that logs the trace.
 *
 * Nothing personal: [what] is a phrase of the call's own, never a title, a name or a path, and
 * what the exception says goes through [ProblemLog.scrub] (addresses, the phone's folders, a
 * picked file's address) and is cut to its first line and [MOST] characters.
 */
object FailureLog {
    /** The tag [ProblemLog] counts as a fault's, with the app's own. */
    const val TAG = "Failure"

    /** The most one line says of what an exception or a reason said, so that one long message does not take the report's room. */
    const val MOST = 240

    private const val OWN = "com.dd3boh.outertune"
    private const val CAUSES = 8

    /**
     * The line for [what] (a phrase such as "Creating a backup"): that it did not work, then what
     * [failure] says, or [why] when there is no exception (a refusal found by a check, an answer
     * that says no), then where in the app's code it came from.
     */
    fun line(what: String, failure: Throwable? = null, why: String? = null): String = buildString {
        append(what.trim()).append(" did not work")
        if (failure != null) {
            val chain = generateSequence(failure) { it.cause }.take(CAUSES).toList()
            val root = chain.last()
            append(": ").append(said(failure))
            if (root !== failure) append(" (caused by ").append(said(root)).append(')')
            // The root's frames say where the failing call began; the failure's own, where it was met.
            (frameOf(root) ?: frameOf(failure))?.let { append(", at ").append(it) }
        } else if (why != null) {
            append(": ").append(clean(why))
        }
    }

    /**
     * Writes [line]. From any thread; never throws, since it is called inside the catch of whatever
     * failed. [Log.e]'s own limit of the line's length is not reached ([MOST] twice and a frame).
     */
    fun record(what: String, failure: Throwable? = null, why: String? = null) {
        runCatching { Log.e(TAG, line(what, failure, why)) }
    }

    /** "IOException: No space left on device", the class without its package and the message's first line. */
    private fun said(failure: Throwable): String {
        val name = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name.substringAfterLast('.') }
        val message = failure.message?.let(::clean)?.takeIf { it.isNotEmpty() }
        return if (message == null) name else "$name: $message"
    }

    /** The first line of [text], without addresses, folders and picked files' addresses, in [MOST] characters. */
    private fun clean(text: String): String =
        ProblemLog.scrub(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()).trim().take(MOST)

    /** "BackupWriter.write(BackupWriter.kt:88)": the first frame of [failure] that is the app's own. */
    private fun frameOf(failure: Throwable): String? = failure.stackTrace.firstOrNull { it.className.startsWith(OWN) }?.let {
        "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})"
    }
}
