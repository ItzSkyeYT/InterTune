/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The launcher's way in.
 *
 * A widget can be added while the app has never run, or while it is playing and knows things it
 * has not bothered to write down, so both ends of that are filled in here: whatever the snapshot
 * is missing when the widget appears, [WidgetStore.hydrate] goes and gets.
 */
class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Held open while it runs. A receiver's process is a candidate for death the moment
        // onReceive returns, and this reads the library and fetches artwork.
        val pending = goAsync()
        scope.launch {
            try {
                WidgetStore.hydrate(context)
            } catch (e: Throwable) {
                Log.w("MusicWidgetReceiver", "Could not fill the widget", e)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
