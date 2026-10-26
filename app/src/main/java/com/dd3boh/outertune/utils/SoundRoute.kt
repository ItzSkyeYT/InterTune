/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Where the music's sound goes, by kind only: the phone's speaker, wired headphones, Bluetooth,
 * USB and so on, never a device's name. For Report a problem (PlayHealth): a stutter over
 * Bluetooth and one on the speaker are not the same fault.
 */
object SoundRoute {
    /** Before Android 13, the output music most likely goes to among those connected: the first of these. */
    @SuppressLint("InlinedApi")
    private val PREFERRED = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_BUS,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    /** The kind of an output ([AudioDeviceInfo.getType]), in words. The numbers are only compared, so any Android has them. */
    @SuppressLint("InlinedApi")
    fun kind(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "the phone's speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "the earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE Audio"
        AudioDeviceInfo.TYPE_HEARING_AID -> "a hearing aid"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "a line out"
        AudioDeviceInfo.TYPE_DOCK -> "a dock"
        AudioDeviceInfo.TYPE_BUS -> "a car's system"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "another app's capture"
        else -> "another output"
    }

    /** Before Android 13, which of the outputs connected ([types]) music most likely goes to. */
    fun likeliest(types: List<Int>): Int? = PREFERRED.firstOrNull { it in types } ?: types.firstOrNull()

    /** Where music goes now, in words, or null when the phone cannot say. Never throws. */
    fun read(context: Context): String? = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val music = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audio.getAudioDevicesForAttributes(music).firstOrNull()?.type
        } else null
        val type = routed ?: likeliest(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }) ?: return null
        kind(type)
    }.getOrNull()
}
