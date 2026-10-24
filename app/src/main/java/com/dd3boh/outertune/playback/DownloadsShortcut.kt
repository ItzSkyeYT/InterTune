/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.freshLaunch

/**
 * The way in to the downloads from the download notifications: a tap opens the app on Downloaded
 * songs, where each song shows up as it lands. The notifications were built with nothing to open,
 * so a tap on them did nothing at all.
 */
object DownloadsShortcut {
    const val ACTION = "dev.skye.intertune.action.DOWNLOADS"

    /** Downloaded songs, the library's own list of them (AutoPlaylistScreen), and its id there. */
    const val PLAYLIST = "downloaded"
    const val ROUTE = "auto_playlist/$PLAYLIST"

    /** Apart from every other PendingIntent of the download service. */
    private const val REQUEST_CODE = 0x0D1

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Whether the launch intent still asks for the downloads: a fresh tap, not a rotation or a
     * restored process, nor the task reopened from recents, which comes back with the same intent.
     */
    fun owed(action: String?, hadSavedInstanceState: Boolean, flags: Int): Boolean =
        action == ACTION && freshLaunch(hadSavedInstanceState, flags)
}
