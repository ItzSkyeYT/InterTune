/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaButtonReceiver
import com.dd3boh.outertune.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * media3's media button receiver, starting the service only when there is something to resume.
 *
 * A play press from a headset, a car or the widget with the app closed comes here, and media3
 * starts the service with startForegroundService and asks it to resume. With no saved queue,
 * persistent queue off from the start or every queue deleted, the resumption is empty, media3
 * shows no notification for an empty player and so never calls startForeground, and Android kills
 * the app ten seconds later for it. Now that press does nothing instead.
 *
 * A running service is left to media3 as before: it holds whatever the app has loaded, which the
 * database cannot say.
 */
class ResumingMediaButtonReceiver : MediaButtonReceiver() {

    private var startService = true

    override fun onReceive(context: Context, intent: Intent?) {
        // The answer is in the database and a receiver runs on the main thread, so the broadcast
        // is held open while it is read.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                startService = MusicService.isRunning || hasSavedQueue(context)
                handleIntentAndMaybeStartTheService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not handle a media button", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        if (!startService) Log.i(TAG, "Nothing to resume, so the media button does not start the player")
        return startService
    }

    /** The same queue onPlaybackResumption would resume. Unreadable counts as none: it would fail there too. */
    private suspend fun hasSavedQueue(context: Context): Boolean = runCatching {
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .database().getResumptionQueue() != null
    }.getOrDefault(false)

    private companion object {
        const val TAG = "ResumingMediaButton"
    }
}
