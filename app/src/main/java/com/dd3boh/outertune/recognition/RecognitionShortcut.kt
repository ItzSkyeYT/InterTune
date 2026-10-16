/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Context
import android.content.Intent
import com.dd3boh.outertune.MainActivity

/**
 * The way in to song recognition from outside the app: the Quick Settings tile and the home screen
 * widget both open the app on the recognition screen, already listening, as the button beside the
 * search bar and then the big button would.
 *
 * The microphone is never started from the tile or the widget themselves. From Android 14 a
 * microphone service cannot start while the app is in the background, and the screen is where the
 * permission is asked and where a run is seen and stopped.
 */
object RecognitionShortcut {
    const val ACTION = "dev.skye.intertune.action.RECOGNISE"

    /** The recognition screen, listening from the moment it opens. */
    const val ROUTE = "recognition/listen"

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether the launch intent still asks to listen: a fresh launch from the tile or the widget,
     * not a rotation or a restored process, nor the task reopened from recents, which comes back
     * with the same intent.
     */
    fun owed(action: String?, hadSavedInstanceState: Boolean, flags: Int): Boolean =
        action == ACTION && !hadSavedInstanceState && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
}
