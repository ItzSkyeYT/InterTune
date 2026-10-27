/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.car.app.notification.CarAppNotificationBroadcastReceiver

/**
 * The way from Android Auto's settings entry into AutoSettingsService. The car library's own
 * pending intent (CarPendingIntent.getCarApp) hands the tap to its receiver, which asks the car to
 * open the screen and throws whatever the car answers back on the main thread. A real head unit
 * refused it on 24 Oct 2026 ("Intent target not a valid settings template"), and the throw took
 * the app and the music down with it. This is the same pending intent, built the way the library
 * builds it for a phone, but sent to [AutoSettingsReceiver], which catches the refusal.
 */
object AutoSettingsLink {
    /** Where the library's receiver finds the screen the tap is for (CarPendingIntent.createForProjected). */
    const val COMPONENT_KEY = "androidx.car.app.notification.COMPONENT_EXTRA_KEY"

    fun forCar(context: Context): PendingIntent {
        val screen = Intent(Intent.ACTION_VIEW).setComponent(ComponentName(context, AutoSettingsService::class.java))
        val tap = Intent(screen).putExtra(COMPONENT_KEY, screen.component).setClass(context, AutoSettingsReceiver::class.java)
        // The library's flags: mutable, since the car fills in its own binder when it sends it.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        if (Build.VERSION.SDK_INT >= 34) flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
        return PendingIntent.getBroadcast(context, 0, tap, flags)
    }
}

/**
 * The car library's receiver for that tap, with the car's refusal caught instead of crashing the app.
 * The library marks its receiver as its own (RestrictedApi), but wrapping it is the only way to catch
 * what it throws: its pending intent is the only one the car accepts for the settings entry.
 */
@SuppressLint("RestrictedApi")
class AutoSettingsReceiver : CarAppNotificationBroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            super.onReceive(context, intent)
        } catch (e: RuntimeException) {
            Log.w("AutoSettings", "The car would not open the settings", e)
        }
    }
}
