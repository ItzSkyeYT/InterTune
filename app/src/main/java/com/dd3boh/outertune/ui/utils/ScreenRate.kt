/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import com.dd3boh.outertune.playback.BatteryUse

/**
 * How often the app's window may be drawn: held down while Battery use is on Saver
 * (BatteryUse.lowFrameRate), the screen's own rate otherwise. His ask of 25 Oct 2026, "cutting
 * down refresh rate of the app to be 30hz instead of 60hz on battery saving".
 *
 * The window is held to the slowest display mode at the size in use that is still at least 30 Hz
 * (BatteryUse.saverScreenMode), and where the display has no faster mode to hold back, 30 is asked
 * as a hint the system may ignore. On Android 15 and later the window also asks for the low frame
 * rate category and no boost to a higher rate on a touch. The category alone would not hold here:
 * Compose (1.9.4) sets its own view's requested rate at the end of every frame it draws and asks
 * for the high category whenever a layer moves, and the platform takes a view's vote after that
 * (View.updateDisplayListIfDirty), so a low category on the Compose view never counts. The display
 * mode is a request to the system's display policy, which those votes cannot raise. Everything is
 * undone at once when the mode changes. A real panel's answer is unverified: the emulator only says
 * what the window asked for.
 */
object ScreenRate {
    private const val TAG = "ScreenRate"

    /** Applies [low] to [activity]'s window. */
    fun apply(activity: Activity, low: Boolean) {
        val window = activity.window ?: return
        runCatching {
            val attributes = window.attributes
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
            if (low && display != null) {
                val current = display.mode
                val modes = display.supportedModes.map { BatteryUse.ScreenMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
                val pick = BatteryUse.saverScreenMode(modes, BatteryUse.ScreenMode(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate))
                attributes.preferredDisplayModeId = pick?.id ?: 0
                // Ignored while a mode is held, so only where there is none.
                attributes.preferredRefreshRate = if (pick == null) BatteryUse.SAVER_FRAME_RATE else 0f
                Log.d(TAG, "Saver: held to mode ${pick?.let { "${it.id} (${it.refreshRate} Hz)" } ?: "none, 30 Hz asked"} of ${modes.joinToString { "${it.id} ${it.width}x${it.height} ${it.refreshRate} Hz" }}")
            } else {
                attributes.preferredDisplayModeId = 0
                attributes.preferredRefreshRate = 0f
            }
            window.attributes = attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.decorView.requestedFrameRate = if (low) View.REQUESTED_FRAME_RATE_CATEGORY_LOW else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                window.setFrameRateBoostOnTouchEnabled(!low)
            }
        }.onFailure { Log.w(TAG, "Could not set the frame rate", it) }
    }
}
