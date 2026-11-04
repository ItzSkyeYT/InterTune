/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dd3boh.outertune.R
import kotlin.math.roundToInt

/**
 * The widgets Settings > Widgets offers to add, as the launcher's own picker shows them: its
 * picture (the receiver's previewLayout) and the size in cells it is dropped at (its
 * targetCellWidth and targetCellHeight). WidgetsSettingsTest holds both to the receivers' XML.
 */
internal enum class OfferedWidget(
    val receiver: Class<*>,
    @param:LayoutRes val picture: Int,
    val cellsWide: Int,
    val cellsHigh: Int,
    /** The size the picture is laid out at, in dp, about the picker's own, before it is scaled down to its place. */
    val drawnWidth: Int,
    val drawnHeight: Int,
) {
    NOW_PLAYING(MusicWidgetReceiver::class.java, R.layout.widget_preview, 4, 2, 288, 228),
    RECOGNITION(RecognitionWidgetReceiver::class.java, R.layout.widget_recognition, 2, 1, 138, 108),
}

/**
 * Whether the home screen takes a widget from the app: Android 8 and later, and a launcher that
 * says it does. Where it does not, Settings > Widgets has no Add and says where to add one instead.
 */
internal fun canPinWidgets(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

/**
 * Asks the launcher for its own box that puts [widget] on the home screen, where the person places
 * it or says no. False when the launcher would not take the request. A player widget goes on from
 * there to its setup sheet, as one dropped from the picker does.
 */
internal fun pinWidget(context: Context, widget: OfferedWidget): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return runCatching {
        AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, widget.receiver), null, null)
    }.onFailure { Log.w("WidgetPicker", "The launcher would not take the widget", it) }.getOrDefault(false)
}

/**
 * [widget]'s picture as the launcher's picker draws it, from its own layout, [width] wide: laid out
 * at about the picker's size and scaled down, so it is the same picture, words and all. A picture
 * only: no touch reaches it and a screen reader passes over it, since its row says what it is.
 */
@Composable
internal fun PickerPicture(widget: OfferedWidget, width: Dp, modifier: Modifier = Modifier) {
    val scale = width.value / widget.drawnWidth
    AndroidView(
        factory = { context ->
            PictureFrame(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                runCatching {
                    val density = resources.displayMetrics.density
                    val picture = RemoteViews(context.packageName, widget.picture).apply(context, this)
                    picture.pivotX = 0f
                    picture.pivotY = 0f
                    picture.scaleX = scale
                    picture.scaleY = scale
                    addView(picture, FrameLayout.LayoutParams((widget.drawnWidth * density).roundToInt(), (widget.drawnHeight * density).roundToInt()))
                }.onFailure { Log.w("WidgetPicker", "Could not draw the widget's picture", it) }
            }
        },
        modifier = modifier.size(width, width * widget.drawnHeight / widget.drawnWidth),
    )
}

/** Holds a picture of a widget and keeps every touch from it, as the setup sheet's preview does. */
private class PictureFrame(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
