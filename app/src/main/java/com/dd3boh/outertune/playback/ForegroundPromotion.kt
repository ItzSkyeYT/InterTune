/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * Whether the download service has become a foreground service, and what happens when Android says
 * it may not.
 *
 * From Android 12 an app in the background may not call Service.startForeground. media3 calls it
 * from inside its own callbacks (the download manager's listener, a handler tick) the moment a
 * download starts, and nothing there can catch the refusal, so the app closed (issue #71: a queued
 * download started while the app was away). Service.startForeground is final, so the service cannot
 * wrap it either, and a refusal that was swallowed would leave media3 believing the notification is
 * up and never trying again. From Android 12 media3's own promotion is therefore switched off
 * (DownloadService.FOREGROUND_NOTIFICATION_ID_NONE, its documented background-only mode) and
 * ExoDownloadService asks for it itself, through [attempt].
 *
 * A refusal is one line in the log, and the service carries on as a plain background service: the
 * downloads run in the process as they did, only without the notification and without a foreground
 * service's protection. The promotion is then owed, and ExoDownloadService asks again whenever a
 * download changes and whenever one of the app's screens comes to the front.
 *
 * Only the refusal is caught: ForegroundServiceStartNotAllowedException is an IllegalStateException.
 * Anything else startForeground throws is a bug and is thrown on.
 *
 * It takes the log as a function, so it is tested without Android.
 */
internal class ForegroundPromotion(private val log: (String) -> Unit) {

    /** The service is a foreground service. */
    @Volatile
    var promoted = false
        private set

    /** The last attempt was refused, and none has gone through since. */
    @Volatile
    var owed = false
        private set

    /**
     * True when the service is a foreground service: it was already, or [promote], the call to
     * startForeground, went through now. False, with one line in the log, when Android refused it
     * because the app is in the background. The line comes once for a run of refusals.
     */
    fun attempt(promote: () -> Unit): Boolean {
        if (promoted) return true
        return try {
            promote()
            promoted = true
            if (owed) log("The refused promotion of the download service to a foreground service has gone through now that the app is in front")
            owed = false
            true
        } catch (e: IllegalStateException) {
            if (!owed) {
                log(
                    "Android refused to make the download service a foreground service, the app is in the background " +
                        "(${e.javaClass.simpleName}): the downloads go on without a notification, and it is asked again when the app is in front"
                )
            }
            owed = true
            false
        }
    }

    companion object {
        /**
         * From this Android version on the service is made a foreground service by [attempt], not
         * by media3, which would throw the refusal where nobody can catch it.
         */
        fun selfPromotes(sdkInt: Int) = sdkInt >= 31
    }
}
