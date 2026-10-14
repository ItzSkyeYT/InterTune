/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.media.AudioDeviceInfo

/**
 * Where the phone itself is sending the music, for the output button to say when nothing is cast.
 *
 * Read from the audio devices that are connected, not asked of the player: Android decides where
 * media plays, and it plays on Bluetooth before a cable and on a cable before its own speaker. The
 * app does not choose among them and does not pretend to: the button says what the system does.
 *
 * [name] is the device's own, where it has one worth showing: a Bluetooth device's is what its
 * owner knows it by. A pair of wired headphones reports the phone's.
 */
internal data class LocalOutput(val kind: Kind, val name: String?) {
    enum class Kind { PHONE, BLUETOOTH, WIRED, OTHER }

    companion object {
        private val BLUETOOTH = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        private val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        private val OTHER = setOf(
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE,
        )

        /** From the output devices connected, each its type and its product name, in any order. */
        fun of(devices: List<Pair<Int, String>>): LocalOutput {
            fun first(types: Set<Int>) = devices.firstOrNull { it.first in types }
            first(BLUETOOTH)?.let { return LocalOutput(Kind.BLUETOOTH, it.second.trim().ifEmpty { null }) }
            // The name a cable reports is the phone's own.
            first(WIRED)?.let { return LocalOutput(Kind.WIRED, null) }
            first(OTHER)?.let { return LocalOutput(Kind.OTHER, it.second.trim().ifEmpty { null }) }
            return LocalOutput(Kind.PHONE, null)
        }
    }
}
