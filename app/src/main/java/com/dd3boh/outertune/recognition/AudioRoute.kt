/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Where our own playback is going, as far as the microphone is concerned.
 *
 * All that is left of an earlier recorder that [MicrophoneListener] replaced. It is kept because
 * the question it answers is not about recording at all: it is what decides whether starting a
 * recognition has to stop the music first.
 */
object AudioRoute {

    /**
     * Whether our own playback is going somewhere the microphone cannot hear.
     *
     * Headphones mean the room and the app are separate: the mic hears the room, our music is in
     * somebody's ears, and there is no reason to stop it. On the phone's own speaker they are the
     * same air, and leaving playback running would identify the song already playing every time.
     *
     * A2DP counts as private even though the profile covers Bluetooth speakers as well as
     * headphones and the device type cannot tell them apart. Headphones are much the commoner
     * case, and being wrong costs one recognition of the song already playing rather than
     * anything worse.
     */
    fun playbackIsPrivate(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_HEARING_AID,
                -> true

                AudioDeviceInfo.TYPE_BLE_HEADSET ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

                else -> false
            }
        }
    }
}
