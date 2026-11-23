/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import androidx.annotation.ColorInt
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DarkMode

/**
 * The colour Android paints for the app where the app draws nothing of its own, in the app's theme.
 *
 * Opening the app from its widget and closing it back into the widget, the launcher grows the app
 * out of the widget and shrinks it back, and fills the edges with the colour of the app's task,
 * which came from the manifest's theme: always light, so a white border went round the widget in
 * the dark theme (#213, Ti-Die, Pixel 11, 16 Nov 2026). The same colour stood behind the pages, and
 * a cold start's splash was white too.
 *
 * Now the theme's own colour follows the phone's dark mode (values-night), a theme picked in the app
 * (Dark, Light, or Battery use's Saver) is the window's from its start on the next opening and the
 * splash's on the next cold start (Android 12 and later), since both come before any of the app is
 * drawn, and the window and the task take the colour of the app's pages as soon as they are drawn and
 * at each change (Android 13 and later for the task; before, it is the window's theme's).
 */
object WindowTheme {
    private const val TAG = "WindowTheme"

    /** The style the splash screen takes: the phone's light or dark, or the one picked in the app. */
    enum class Start { PHONE, LIGHT, DARK }

    /** What [darkMode] asks of the splash. [alwaysDark]: Battery use is on Saver, which is always dark. */
    fun start(darkMode: DarkMode, alwaysDark: Boolean): Start = when {
        alwaysDark || darkMode == DarkMode.ON -> Start.DARK
        darkMode == DarkMode.OFF -> Start.LIGHT
        else -> Start.PHONE
    }

    /** Android refuses a task colour that is not opaque. */
    @ColorInt
    fun opaque(@ColorInt argb: Int): Int = argb or 0xFF000000.toInt()

    /** The window behind the pages and the task's colour, in [background], the colour of the pages. */
    fun paint(activity: Activity, @ColorInt background: Int) {
        val colour = opaque(background)
        runCatching {
            activity.window.setBackgroundDrawable(ColorDrawable(colour))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The label and icon stay the app's own (none given), the primary colour is the same.
                activity.setTaskDescription(
                    ActivityManager.TaskDescription.Builder().setPrimaryColor(colour).setBackgroundColor(colour).build()
                )
            }
        }.onFailure { Log.w(TAG, "Could not give the window the theme's colour", it) }
    }

    private const val PREFS = "window_theme"
    private const val KEY_START = "start"

    /**
     * The theme the window starts in, before anything is drawn: the one picked in the app, as last
     * written by [startWith], so the window and the task have its colour from the first frame, on every
     * Android the app runs on. Null keeps the manifest's, which follows the phone. For setTheme, before
     * the activity's onCreate goes on.
     */
    fun startTheme(context: Context): Int? = when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_START, null)) {
        Start.DARK.name -> R.style.Theme_OuterTune_StartDark
        Start.LIGHT.name -> R.style.Theme_OuterTune_StartLight
        else -> null
    }

    /**
     * The next cold start in [start]: its window ([startTheme]) and, from Android 12, its splash, which
     * Android keeps, so it is only told again when it changes. The last one told is written down here,
     * by name. Told once in any case when nothing is written down (a first start, or the app's data
     * cleared, which may leave Android's).
     */
    fun startWith(activity: Activity, start: Start) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_START, null) == start.name) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val style = when (start) {
                Start.PHONE -> Resources.ID_NULL
                Start.LIGHT -> R.style.Theme_OuterTune_StartLight
                Start.DARK -> R.style.Theme_OuterTune_StartDark
            }
            val told = runCatching { activity.splashScreen.setSplashScreenTheme(style) }
                .onFailure { Log.w(TAG, "Could not give the splash screen the app's theme", it) }
                .isSuccess
            if (!told) return
        }
        prefs.edit().putString(KEY_START, start.name).apply()
    }
}
