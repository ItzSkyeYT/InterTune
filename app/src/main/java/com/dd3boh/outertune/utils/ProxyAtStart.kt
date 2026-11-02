/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.playback.MusicService
import kotlin.system.exitProcess

/**
 * The proxy as the app started with it. Every client that talks to YouTube takes the proxy once,
 * when the app starts (App.onCreate), so a change on the settings page waits for a restart, and
 * nothing said so: a proxy set up to reach YouTube Music from South Korea (#68) looked like it did
 * nothing. The page compares what is set now with this and offers the restart while they differ.
 */
object ProxyAtStart {
    /** [of] the stored setting when the app started, or null before App.onCreate noted it. */
    @Volatile
    var setting: String? = null

    /** One string for a stored proxy setting: off is one value whatever the fields still hold. */
    fun of(enabled: Boolean?, type: String?, url: String?): String =
        if (enabled != true) "off" else "${type ?: "HTTP"} ${url.orEmpty().trim()}"

    /**
     * Ends the app and opens it again, as a restored backup does (BackupRestoreViewModel): the
     * music stops, and the new process takes the proxy as it starts.
     */
    fun restart(context: Context) {
        context.stopService(Intent(context, MusicService::class.java))
        context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
        exitProcess(0)
    }
}
