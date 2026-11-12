/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/** What the TV calls the phone on its idle screen ("Mel's Pixel"). */
object PhoneName {
    /** The longest name the TV is told: a pill on a screen, not a sentence. */
    const val MAX_LENGTH = 40

    /**
     * The name the phone's owner gave it in the system settings ([deviceName], Settings.Global.DEVICE_NAME),
     * or its model ([model], Build.MODEL) when it has none, or "Phone" when even that is empty.
     */
    fun of(deviceName: String?, model: String?): String {
        val chosen = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: model?.trim()?.takeIf { it.isNotEmpty() } ?: "Phone"
        return chosen.take(MAX_LENGTH).trimEnd()
    }
}
