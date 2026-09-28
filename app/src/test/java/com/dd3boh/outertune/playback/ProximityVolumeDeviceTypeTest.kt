/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.media.AudioDeviceInfo
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quieter as you walk away should engage over LE Audio headphones the same way it already does
 * over classic A2DP ones, on the SDK floor the feature already requires for BLUETOOTH_SCAN.
 */
class ProximityVolumeDeviceTypeTest {

    @Test
    fun `classic A2DP is a match on any of the checked SDK levels`() {
        assertTrue(
            ProximityVolume.isHeadphoneOutputType(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                sdkInt = Build.VERSION_CODES.R,
            )
        )
        assertTrue(
            ProximityVolume.isHeadphoneOutputType(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                sdkInt = Build.VERSION_CODES.S,
            )
        )
    }

    @Test
    fun `LE Audio matches from Android S, same floor hasPermission already requires`() {
        assertTrue(
            ProximityVolume.isHeadphoneOutputType(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                sdkInt = Build.VERSION_CODES.S,
            )
        )
        assertTrue(
            ProximityVolume.isHeadphoneOutputType(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                sdkInt = Build.VERSION_CODES.S + 1,
            )
        )
    }

    @Test
    fun `LE Audio below Android S is not matched, matching hasPermission's own gate`() {
        assertFalse(
            ProximityVolume.isHeadphoneOutputType(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                sdkInt = Build.VERSION_CODES.R,
            )
        )
    }

    @Test
    fun `an unrelated output type never matches`() {
        assertFalse(
            ProximityVolume.isHeadphoneOutputType(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                sdkInt = Build.VERSION_CODES.S,
            )
        )
    }
}
