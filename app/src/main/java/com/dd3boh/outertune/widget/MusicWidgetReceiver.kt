/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
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
 * has not bothered to write down, so both ends of that are filled in here: the widget is drawn at
 * once from what is written, and whatever the snapshot is missing when the widget appears,
 * [WidgetStore.hydrate] goes and gets. Android sends this the moment a widget is bound, which on
 * the Pixel 5 is before its setup sheet opens (the sheet's Done draws it again with its settings),
 * and the first widget's onEnabled comes just before it, so nothing more is needed there.
 */
class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The launcher has given a widget a new size (its owner let go of a resize, or the phone turned).
     * Glance then composes the widget for it on the main thread; the pictures that need drawing for the
     * new size (a crate, a row of records, the blurred cover) are drawn here at once on workers, while
     * Glance is still starting its session, so the composition finds them done.
     */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        Log.i(WidgetStore.DRAW_TAG, "Widget $appWidgetId has a new size: ${sizesOf(newOptions).joinToString { "${it.first}x${it.second}" }}")
        WidgetStore.warmResizedSoon(context, appWidgetId, newOptions)
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.i(WidgetStore.DRAW_TAG, "Asked to draw widgets ${appWidgetIds.joinToString()}")
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Not held open with goAsync, which this used to call: super has already taken the
        // broadcast's one pending result, so a second call returned null and held nothing. What
        // keeps the process up while this reads the library and fetches artwork is the Glance
        // session super just started, which runs as a WorkManager job.
        scope.launch {
            try {
                WidgetStore.hydrate(context)
            } catch (e: Throwable) {
                Log.w("MusicWidgetReceiver", "Could not fill the widget", e)
            }
        }
    }
}
