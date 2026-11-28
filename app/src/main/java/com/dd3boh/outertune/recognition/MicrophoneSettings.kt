/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * InterTune's page in Android's settings, where a refused microphone is allowed again. After two
 * refusals Android stops asking and answers no by itself, with nothing on screen, so from then on
 * this page is the only way to allow it.
 */
object MicrophoneSettings {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** [ownTask] for a screen that closes as the page opens, as the tile's does. */
    fun open(context: Context, ownTask: Boolean = false) {
        val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        if (ownTask) page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(page) }
            .onFailure { Log.w("MicrophoneSettings", "Could not open Android's page for the app", it) }
    }
}
