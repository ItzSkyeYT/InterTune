/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.os.Build
import java.util.Locale

/** What this phone is, as far as choosing an audience goes. Compared on the phone, never sent. */
data class DeviceFacts(
    val sdk: Int,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
) {
    companion object {
        fun current() = DeviceFacts(
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
        )
    }
}

/**
 * Who a question or an announcement is for, beyond the app versions it already names: a range of
 * Android versions, and a list of devices.
 *
 * Everybody fetches the same document and each phone decides for itself whether an entry is meant
 * for it, so asking only some phones tells nobody which phone is which.
 */
data class Audience(
    /** Android API levels, both ends included: 34 is Android 14. */
    val minSdk: Int = 0,
    val maxSdk: Int = Int.MAX_VALUE,
    /** Empty means every device. Otherwise one entry has to match, see [includes]. */
    val devices: List<String> = emptyList(),
) {
    /**
     * A device entry is compared, ignoring case, with the manufacturer, the brand, the model, the
     * device's code name, and manufacturer and model together, and has to be one of them whole. A
     * `*` stands for any run of characters: "samsung" is every Samsung, "SM-S93*" a family of
     * models, "pixel 10*" every Pixel 10.
     */
    fun includes(phone: DeviceFacts): Boolean {
        if (phone.sdk < minSdk || phone.sdk > maxSdk) return false
        if (devices.isEmpty()) return true
        val names = listOf(phone.manufacturer, phone.brand, phone.model, phone.device, "${phone.manufacturer} ${phone.model}")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        return devices.any { entry ->
            val pattern = entry.trim().lowercase(Locale.ROOT)
            pattern.isNotEmpty() && names.any { wildcardMatches(pattern, it) }
        }
    }
}

/** Whether [text] is [pattern], where each `*` in the pattern stands for any run of characters, an empty one included. */
internal fun wildcardMatches(pattern: String, text: String): Boolean {
    val parts = pattern.split('*')
    if (parts.size == 1) return pattern == text
    if (!text.startsWith(parts.first())) return false
    var at = parts.first().length
    for (i in 1 until parts.lastIndex) {
        val found = text.indexOf(parts[i], at)
        if (found < 0) return false
        at = found + parts[i].length
    }
    val tail = parts.last()
    return text.length - at >= tail.length && text.endsWith(tail)
}
