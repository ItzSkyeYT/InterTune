/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package com.dd3boh.outertune.recognition

import android.content.Intent
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log

/**
 * A Quick Settings tile that survives the phone's Quick Settings going away while it binds (#227).
 *
 * The tile is bound by SystemUI. When SystemUI dies at that moment, Android's own TileService.onBind
 * throws "Unable to reach IQSService" (a DeadObjectException underneath), and that closed the app: an
 * OPPO on Android 9 with 0.12.11. Nothing is bound this time instead, and SystemUI binds the tile again
 * once it is back.
 */
abstract class GuardedTileService : TileService() {
    override fun onBind(intent: Intent?): IBinder? = try {
        super.onBind(intent)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Quick Settings went away while the tile was being bound", e)
        null
    }

    private companion object {
        const val TAG = "GuardedTileService"
    }
}
