/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.dd3boh.outertune.recognition.RecognitionShortcut
import com.dd3boh.outertune.utils.AppLanguage

/**
 * The recognition button for the home screen: one tap opens the recognition screen, listening
 * (RecognitionShortcut). Apart from the player widget on purpose. It shows nothing that changes,
 * so it is drawn with plain RemoteViews, and drawn again only for the app's language: at each
 * change and at each start (App).
 *
 * A text the layout names is looked up by the launcher, in the phone's language, never in a
 * language chosen for the app (App language), so then the app puts its words in itself.
 */
class RecognitionWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = views(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    companion object {
        /** Apart from the player widget's own requests, so neither replaces the other's intent. */
        private const val REQUEST_CODE = 4244

        /** The button, with its words in the language [context] speaks (the app's own). */
        private fun views(context: Context): RemoteViews {
            val tap = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                RecognitionShortcut.intent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return RemoteViews(context.packageName, R.layout.widget_recognition).apply {
                setOnClickPendingIntent(R.id.widget_recognition_button, tap)
                // On the phone's language the launcher's own look-up is right, and follows the
                // phone by itself; a language of the app's own is put in here.
                if (AppLanguage.current(context) != SYSTEM_DEFAULT) {
                    val words = context.getString(R.string.recognise)
                    setTextViewText(R.id.widget_recognition_label, words)
                    setContentDescription(R.id.widget_recognition_button, words)
                }
            }
        }

        /** Every button on the home screens drawn again: after a change of the app's language (App). */
        fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RecognitionWidgetReceiver::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
