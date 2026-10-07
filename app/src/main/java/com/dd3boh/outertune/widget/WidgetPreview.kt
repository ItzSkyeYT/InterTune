/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.compose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The widget as the launcher would draw it, at any size and with any settings, without a widget.
 *
 * Glance composes exactly the views it would send to the launcher, so what the settings screen
 * shows is the widget itself rather than a picture of it: the same snapshot, the same layout rules
 * and the same colours.
 */
suspend fun renderWidget(
    context: Context,
    settings: WidgetSettings,
    widthDp: Int,
    heightDp: Int,
    /** A snapshot to draw instead of the real one, such as nothing playing at all. */
    drawn: WidgetStore.Drawn? = null,
): RemoteViews {
    val state = mutablePreferencesOf().also { WidgetKeys.write(it, settings) }
    return MusicWidget(drawn).compose(context, size = DpSize(widthDp.dp, heightDp.dp), state = state)
}

/**
 * What [draw] made, for a preview that is drawn again at every change of a setting: its views, or
 * null when the drawing failed, which is logged. When a newer change has cancelled this drawing
 * meanwhile it gives nothing at all: the cancellation is thrown on, whatever [draw] did, so the
 * line that asked never puts an old drawing, or the nothing of a cancelled one, over the newer.
 *
 * This was a runCatching round the drawing, which catches a cancellation as it does a failure.
 * Every change made while a drawing was in flight emptied the preview until the next one landed,
 * and a cancelled drawing that came back after the newer one (the first of a session reads the
 * snapshot and decodes its covers off the main thread, which is not cut short) left it empty
 * until the next change.
 */
internal suspend fun <T : Any> drawnIfStillWanted(draw: suspend () -> T): T? {
    val drawn = runCatching { draw() }
    // However it ended, well, badly or by being cancelled, none of it is wanted any more.
    currentCoroutineContext().ensureActive()
    return drawn.onFailure { Log.w("WidgetConfig", "Could not draw the preview", it) }.getOrNull()
}
