/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Closes the Quick Settings panel and opens [intent]. */
@SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.openCollapsing(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Android 14 takes only a PendingIntent here and throws on the plain intent.
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
