/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaButtonReceiver
import com.dd3boh.outertune.constants.PlayingQueueKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
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
 *
 * A wired headset's button, the hook, goes to media3 as play/pause ([handedOn]): media3 lets the
 * hook start the service and then has no case for it, so the press started a player that played
 * nothing, and Android closed the app 30 s later.
 */
class ResumingMediaButtonReceiver : MediaButtonReceiver() {

    private var startService = true

    /** The key that came is the headset's hook, which media3 is given as play/pause ([handedOn]). */
    private var hook = false

    override fun onReceive(context: Context, intent: Intent?) {
        // The answer is in the database and a receiver runs on the main thread, so the broadcast
        // is held open while it is read.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                startService = MusicService.isRunning || hasSavedQueue(context)
                val handed = handedOn(intent)
                hook = handed !== intent
                handleIntentAndMaybeStartTheService(context, handed)
            } catch (e: Exception) {
                Log.w(TAG, "Could not handle a media button", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        // Which key it was, said here since the service sees play/pause: once a press, media3 asks for the press only.
        if (hook) Log.i(TAG, "The key is the headset's hook, handed on as play/pause")
        if (!startService) Log.i(TAG, "Nothing to resume, so the media button does not start the player")
        else Log.i(TAG, if (MusicService.isRunning) "The media button goes to the player running" else "The media button starts the player")
        return startService
    }

    companion object {
        private const val TAG = "ResumingMediaButton"

        /**
         * The key media3 is given for [keyCode]: play/pause for a headset's hook, which is what the
         * hook means, and any other key as it is. media3 1.8.0 decides a key that comes to the
         * service as a start in MediaSessionImpl.applyMediaButtonKeyEvent, which knows play/pause,
         * play, pause, next, previous, forward, rewind and stop, and not the hook: it was dropped
         * ("Ignored unrecognized media button intent"), nothing was resumed, and the service this
         * receiver had started stood with an empty player until Android closed the app for it (30 s
         * on Android 13 and later) or, since 0.12.10, until it gave up by itself. Play/pause, sent
         * the same way, resumes the saved queue and plays (Android 17 emulator, 12 Nov 2026). A
         * car's AUX cable can press the hook by itself: a plug with three rings in a socket for
         * four shorts what a headset's button shorts (#153).
         */
        fun keyHandedOn(keyCode: Int): Int =
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else keyCode

        /**
         * [intent] as media3 is given it: the same media button with the key of [keyHandedOn], the
         * press and its release alike, on a copy. An intent with no key in it goes on as it came.
         */
        internal fun handedOn(intent: Intent?): Intent? {
            val sent = intent ?: return null
            val event = runCatching { IntentCompat.getParcelableExtra(sent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }.getOrNull()
                ?: return intent
            val key = keyHandedOn(event.keyCode)
            if (key == event.keyCode) return intent
            return Intent(sent).putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(event.downTime, event.eventTime, event.action, key, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source),
            )
        }

        /**
         * Whether there is a saved queue to resume: the one onPlaybackResumption would load.
         * Unreadable counts as none, since resuming would fail on it too.
         */
        suspend fun hasSavedQueue(context: Context): Boolean = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                .database().getResumptionQueue(context.dataStore[PlayingQueueKey]) != null
        }.getOrDefault(false)
    }
}
