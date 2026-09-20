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
import java.io.File

/**
 * Records how strong the Bluetooth signal from nearby devices is, over time.
 *
 * Exists to answer one question before anything is built on it: is received signal strength a
 * usable proxy for how far away the listener has walked? The honest prior is no. A human body
 * between two radios at 2.4 GHz costs ten to twenty decibels, which is more than walking several
 * metres does, so turning on the spot should move the number as much as crossing the room. Rooms
 * also add their own ten decibels of multipath to a radio that has not moved at all. If that is
 * what the data shows, the idea dies here rather than shipping as a volume that lurches when you
 * turn round.
 *
 * Measured rather than argued because the last several things guessed at in this area turned out
 * the other way when someone finally looked.
 *
 * Writes a CSV. Debug fixture, not a feature: nothing calls it unless someone presses the button.
 */
class ProximityProbe(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var file: File? = null
    private var startedAt = 0L
    private var samples = 0

    val isRunning: Boolean
        get() = file != null

    /** Whether the scan permission is in hand. Requested at the point of use, not at install. */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start(): File? {
        if (isRunning) return file
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return null
        if (!hasPermission()) return null

        val out = File(context.getExternalFilesDir(null), "rssi-${System.currentTimeMillis()}.csv")
        out.writeText("elapsed_ms,address,name,rssi_dbm\n")
        file = out
        startedAt = SystemClock.elapsedRealtime()
        samples = 0

        // Lowest latency the radio offers, because the question is about how much the number moves
        // moment to moment, and a slow scan would smooth away exactly what is being measured.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }
            .onFailure { Log.w(TAG, "scan refused: ${it.message}"); file = null }
        return file
    }

    @SuppressLint("MissingPermission")
    fun stop(): File? {
        val out = file ?: return null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        file = null
        Log.d(TAG, "logged $samples samples to ${out.absolutePath}")
        return out
    }

    /** How long it has been running and how much it has seen, for the button to show. */
    fun status(): Pair<Long, Int> = (SystemClock.elapsedRealtime() - startedAt) to samples

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val out = file ?: return
            // Everything, not just the headphones. Other radios in the room that are not moving
            // are the control: whatever they do is the room and the body, not the distance.
            val name = runCatching { result.device.name }.getOrNull()
                ?: result.scanRecord?.deviceName
                ?: ""
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            runCatching {
                out.appendText("$elapsed,${result.device.address},${name.replace(',', ' ')},${result.rssi}\n")
                samples++
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            file = null
        }
    }

    companion object {
        private const val TAG = "ProximityProbe"
    }
}
