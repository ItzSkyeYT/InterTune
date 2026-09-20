/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow

/**
 * Turns the music down as the listener walks away from their phone.
 *
 * The conceit is that the phone is where the sound is coming from, which pairs with a soundstage
 * that already stays put in the room: face the phone, recentre, and walking off actually behaves
 * like walking away from a speaker.
 *
 * Whether radio signal strength can carry that at all was measured rather than assumed, and the
 * answer was not the expected one. A body between two radios at 2.4 GHz really is worth ten to
 * twenty decibels, so turning on the spot throws individual readings around by eighteen; the
 * mistake was thinking that ruins the measurement. It is close to symmetric, so the median barely
 * moves: -49 dBm sitting beside the phone, -51 turning on the spot, -79 across the flat. Only
 * 0.6% of near readings were as weak as a far one, and a radio in the same room that never moved
 * drifted 2 dB over the whole run, so the 30 dB is distance and not the room.
 *
 * Hence the two decisions that matter here. The filter is a median and not a mean, because the
 * outliers a body creates are exactly what a mean would chase. And the window is long, because
 * ten seconds of it cuts the wobble to 6 dB against 31 dB of real range, which is five to one.
 *
 * Deliberately gentle. Thirty decibels of measurement drives about eight of volume, so what is
 * left of the noise moves the level by a decibel or so, under what anyone notices, while the
 * distance still spans the whole range. Music that lurches when you turn round would be worse
 * than no feature at all.
 */
class ProximityVolume(private val context: Context) {

    /** Multiply into the output gain. One when near, or whenever this is not running. */
    val factor = MutableStateFlow(1f)

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var running = false
    private var targetName: String? = null

    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Int>()

    /**
     * The strongest signal seen lately, taken as "next to the phone".
     *
     * Self-calibrating on purpose. The measured numbers hold their shape between rooms but not
     * their absolute values, since how strong the signal is up close depends on the phone, the
     * headphones and what is in the way, so a hardcoded reference would be wrong everywhere but
     * one flat. It rises the instant something stronger arrives, which is what happens when
     * someone walks back, and falls slowly enough that one lucky reflection cannot strand the
     * volume low for the rest of the album.
     */
    private var nearReference = Float.NEGATIVE_INFINITY
    private var lastDecayAt = 0L

    val isAvailable: Boolean
        get() = adapter?.isEnabled == true && hasPermission()

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun connectedHeadphoneName(): String? {
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return null
        val outputs = runCatching {
            audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return null
        return outputs.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }?.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean = runCatching { startInternal() }.getOrElse {
        // A refused permission, Bluetooth turned off mid-song, a vendor stack that throws where
        // the documentation says it returns. None of it is worth stopping the music for.
        Log.w(TAG, "could not start: ${it.message}")
        running = false
        factor.value = 1f
        false
    }

    @SuppressLint("MissingPermission")
    private fun startInternal(): Boolean {
        if (running) return true
        if (!hasPermission()) return false
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false

        // Which headphones to watch. They advertise under a name derived from the paired one, but
        // from a private address that rotates, so the name is the only stable handle.
        // Asked of the audio routing rather than of Bluetooth. Both can name the headphones, but
        // every route into BluetoothAdapter needs BLUETOOTH_CONNECT on top of the scan permission,
        // and this one needs nothing at all. It also answers a better question: not what is paired
        // but what is actually playing the music.
        targetName = connectedHeadphoneName() ?: return false

        times.clear()
        values.clear()
        nearReference = Float.NEGATIVE_INFINITY
        lastDecayAt = SystemClock.elapsedRealtime()
        running = true

        // Balanced rather than low latency: ten seconds of median needs a reading a second, not
        // ten, and the radio is on someone's head for hours at a time.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }
            .onFailure { Log.w(TAG, "scan refused: ${it.message}"); running = false }
        return running
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        factor.value = 1f
    }

    /** Whether the scan is live, so the setting can show what is actually happening. */
    val isRunning: Boolean
        get() = running

    private fun accept(rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        times.addLast(now)
        values.addLast(rssi)
        while (times.isNotEmpty() && now - times.first() > WINDOW_MS) {
            times.removeFirst()
            values.removeFirst()
        }
        if (values.size < MIN_SAMPLES) return

        val sorted = values.sorted()
        val median = sorted[sorted.size / 2].toFloat()

        if (median > nearReference) {
            nearReference = median
        } else {
            val seconds = (now - lastDecayAt) / 1000f
            nearReference -= REFERENCE_DECAY_PER_SECOND * seconds
        }
        lastDecayAt = now

        val drop = (nearReference - median).coerceIn(0f, RANGE_DB)
        val attenuation = MAX_ATTENUATION_DB * (drop / RANGE_DB)
        factor.value = 10f.pow(-attenuation / 20f)
    }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val target = targetName ?: return
            val name = result.scanRecord?.deviceName ?: return
            // "WH-1000XM5" pairs, "LE_WH-1000XM5" advertises. Contains rather than equals.
            if (!name.contains(target, ignoreCase = true)) return
            accept(result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            running = false
            factor.value = 1f
        }
    }

    companion object {
        private const val TAG = "ProximityVolume"

        /** Ten seconds cut the turning wobble to 6 dB against 31 dB of range, measured. */
        const val WINDOW_MS = 10_000L
        const val MIN_SAMPLES = 5

        /** What crossing a flat was worth. Beyond this the audio drops out anyway. */
        const val RANGE_DB = 30f

        /** Gentle on purpose: the residual noise then moves the level by about a decibel. */
        const val MAX_ATTENUATION_DB = 8f

        /** Slow enough that one lucky reflection cannot strand the volume low for an album. */
        const val REFERENCE_DECAY_PER_SECOND = 1f / 60f
    }
}
