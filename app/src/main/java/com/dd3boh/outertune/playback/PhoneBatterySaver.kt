/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dd3boh.outertune.constants.BatteryMode
import com.dd3boh.outertune.constants.BatteryModeKey
import com.dd3boh.outertune.constants.FollowPhoneSaverKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone's own battery saver is on, for Battery use (BatteryUse.effective): while it is,
 * and the switch under Battery use lets it in, the app runs as Saver. His ask of 25 Oct 2026.
 *
 * Read from PowerManager when the app starts, again whenever the activity starts or resumes, and
 * kept by a receiver for ACTION_POWER_SAVE_MODE_CHANGED registered at runtime for as long as the
 * process lives (the broadcast is not delivered to receivers in the manifest). A frozen process gets
 * the broadcast when it thaws; the reads on start and resume cover anything missed. Samsung's Power
 * saving is Android's battery saver underneath, so it should read the same, but it is unverified on
 * a real phone.
 */
object PhoneBatterySaver {
    private val state = MutableStateFlow(false)

    /** The phone's battery saver as last read. */
    val on: StateFlow<Boolean> = state.asStateFlow()

    @Volatile private var listening = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            read(context)
        }
    }

    /** Reads the state and starts listening for changes, once per process. Safe to call again. */
    fun start(context: Context) {
        read(context)
        if (listening) return
        synchronized(this) {
            if (listening) return
            runCatching {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    receiver,
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    // A system broadcast still reaches a receiver that is not exported.
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                listening = true
            }
        }
    }

    /** Reads the phone's battery saver again, as on start and resume. */
    fun read(context: Context) {
        state.value = isOn(context)
    }

    /** The phone's battery saver right now, from PowerManager: off when it cannot be read. */
    fun isOn(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }.getOrDefault(false)
}

/**
 * Battery use in force right now: the mode picked, the switch under it and the phone's battery
 * saver, as BatteryUse.modeOf has them. From the settings in memory and one look at PowerManager,
 * for the readers that ask when they act (the library sync, downloads, the song cache, Home's rows).
 */
fun Context.batteryModeNow(): BatteryMode =
    BatteryUse.modeOf(dataStore[BatteryModeKey], dataStore[FollowPhoneSaverKey], PhoneBatterySaver.isOn(this))
