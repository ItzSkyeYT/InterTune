/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recognition.RecognitionShortcut

/**
 * The recognition button for the home screen: one tap opens the recognition screen, listening
 * (RecognitionShortcut). Apart from the player widget on purpose. It shows nothing that changes,
 * so it is drawn once with plain RemoteViews and never updated by the app.
 */
class RecognitionWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val tap = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            RecognitionShortcut.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_recognition).apply {
            setOnClickPendingIntent(R.id.widget_recognition_button, tap)
        }
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    private companion object {
        /** Apart from the player widget's own requests, so neither replaces the other's intent. */
        const val REQUEST_CODE = 4244
    }
}
