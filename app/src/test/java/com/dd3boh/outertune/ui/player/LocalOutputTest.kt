/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOutputTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "SM-S938B"
    private val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to "SM-S938B"

    @Test
    fun `a phone by itself plays through itself`() {
        assertEquals(LocalOutput(LocalOutput.Kind.PHONE, null), LocalOutput.of(listOf(earpiece, speaker)))
        assertEquals(LocalOutput(LocalOutput.Kind.PHONE, null), LocalOutput.of(emptyList()))
    }

    @Test
    fun `Bluetooth headphones are named as their owner knows them, wherever they come in the list`() {
        val headphones = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "WH-1000XM5"
        assertEquals(LocalOutput(LocalOutput.Kind.BLUETOOTH, "WH-1000XM5"), LocalOutput.of(listOf(speaker, headphones)))
        assertEquals(LocalOutput(LocalOutput.Kind.BLUETOOTH, "WH-1000XM5"), LocalOutput.of(listOf(headphones, earpiece, speaker)))
        // One that will not say its name is still Bluetooth.
        assertEquals(LocalOutput(LocalOutput.Kind.BLUETOOTH, null), LocalOutput.of(listOf(speaker, AudioDeviceInfo.TYPE_BLE_HEADSET to " ")))
    }

    @Test
    fun `Bluetooth comes before a cable, and a cable before the speaker, as Android plays them`() {
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "SM-S938B"
        val headphones = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "WH-1000XM5"
        assertEquals(LocalOutput.Kind.BLUETOOTH, LocalOutput.of(listOf(speaker, wired, headphones)).kind)
        // A cable reports the phone's own name, which is not the headphones'.
        assertEquals(LocalOutput(LocalOutput.Kind.WIRED, null), LocalOutput.of(listOf(speaker, wired)))
        assertEquals(LocalOutput(LocalOutput.Kind.OTHER, "LG TV"), LocalOutput.of(listOf(speaker, AudioDeviceInfo.TYPE_HDMI to "LG TV")))
    }
}
