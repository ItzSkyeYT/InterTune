/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
import android.media.AudioDeviceInfo.TYPE_DOCK
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_HDMI_ARC
import android.media.AudioDeviceInfo.TYPE_HDMI_EARC
import android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL
import android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which outputs the phone's low-power audio path is trusted with (#90). */
class SoundRouteTest {

    private val phone = listOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_SPEAKER_SAFE)

    @Test
    fun `the phone's own outputs, a headphone jack and Bluetooth keep the low-power path`() {
        assertTrue(SoundRoute.offloadSafe(phone))
        assertTrue(SoundRoute.offloadSafe(phone + TYPE_BLUETOOTH_A2DP))
        assertTrue(SoundRoute.offloadSafe(phone + TYPE_BLE_HEADSET))
        assertTrue(SoundRoute.offloadSafe(phone + TYPE_WIRED_HEADPHONES))
    }

    @Test
    fun `USB and the other digital cables do not, whatever else is connected`() {
        for (cable in listOf(TYPE_USB_HEADSET, TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_DOCK, TYPE_HDMI, TYPE_HDMI_ARC, TYPE_HDMI_EARC, TYPE_LINE_DIGITAL)) {
            assertFalse("type $cable", SoundRoute.offloadSafe(phone + cable))
            assertFalse("type $cable beside Bluetooth", SoundRoute.offloadSafe(phone + TYPE_BLUETOOTH_A2DP + cable))
        }
    }
}
