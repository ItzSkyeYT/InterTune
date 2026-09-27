/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.compose

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
