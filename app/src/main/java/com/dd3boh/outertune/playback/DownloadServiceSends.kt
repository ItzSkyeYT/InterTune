/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * Every message the app sends to the download service goes through here, so that Android refusing
 * one never closes the app.
 *
 * media3's senders (DownloadService.sendResumeDownloads and the others) start the service with a
 * bare startService, which Android refuses while the app is in the background: an
 * IllegalStateException, a BackgroundServiceStartNotAllowedException from Android 12. Wherever
 * nothing catches it, the process dies. Issue #65 was the automatic scan: its last step resumes
 * the downloads, on a worker thread, and it ran after the person had left for the update installer.
 *
 * Only that refusal is caught. Anything else a send throws is a bug and is thrown on.
 *
 * A refused resume is the one message that has to come again. media3 starts with downloads paused
 * and the service resumes them as it is created, so without the resume they stay paused until the
 * person starts a download or opens the app afresh. [resumeOwed] says one is owed, and the activity
 * sends it again once it is in front (MainActivity.onResume). The other messages answer a tap, so
 * a refused one is only logged.
 *
 * It takes the log as a function, so it is tested without Android.
 */
internal class DownloadServiceSends(private val log: (String) -> Unit) {

    /** The last resume was refused, and none has gone through since. */
    @Volatile
    var resumeOwed = false
        private set

    /**
     * Runs [send], one message to the download service. False, with a line in the log, when Android
     * refused to start the service because the app is in the background; true when it went through.
     * [what] names the message in that line.
     */
    fun attempt(what: String, send: () -> Unit): Boolean = try {
        send()
        true
    } catch (e: IllegalStateException) {
        log("Android refused to start the download service for $what, the app is in the background (${e.javaClass.simpleName})")
        false
    }

    /** The resume of the downloads. When refused it stays owed, for the next time the app is in front. */
    fun resume(send: () -> Unit) {
        val wasOwed = resumeOwed
        resumeOwed = !attempt("resuming the downloads", send)
        if (wasOwed && !resumeOwed) log("The refused resume of the downloads has gone through now that the app is in front")
    }
}
