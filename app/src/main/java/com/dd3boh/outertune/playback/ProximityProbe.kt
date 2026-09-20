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
import android.speech.tts.TextToSpeech
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

    /**
     * The run, as an ordered set of things to do and how long to do them for.
     *
     * Scripted rather than left to the person holding the phone, because the comparison only
     * works if the segments are comparable, and because the readings are useless unless it is
     * known which was taken while standing still and which while walking. Each row carries its
     * step number for that reason.
     */
    private var steps: List<Step> = emptyList()
    private var spokenStep = -1

    data class Step(val spoken: String, val seconds: Int)

    /** Spoken, because nobody can read a phone screen while walking away from it. */
    private var speech: TextToSpeech? = null
    private var speechReady = false

    val isRunning: Boolean
        get() = file != null

    /** Which step is running, and how long is left of it. Null once the run is over. */
    fun currentStep(): Pair<Step, Int>? {
        if (!isRunning) return null
        var remaining = (SystemClock.elapsedRealtime() - startedAt) / 1000
        for (step in steps) {
            if (remaining < step.seconds) return step to (step.seconds - remaining).toInt()
            remaining -= step.seconds
        }
        return null
    }

    private fun stepIndex(): Int {
        var remaining = (SystemClock.elapsedRealtime() - startedAt) / 1000
        for ((i, step) in steps.withIndex()) {
            if (remaining < step.seconds) return i
            remaining -= step.seconds
        }
        return steps.size
    }

    /**
     * Call regularly while running. Announces each step as it begins and stops at the end.
     *
     * Driven from outside rather than by a timer of its own so that it cannot outlive the screen
     * that started it.
     */
    fun tick(onFinished: () -> Unit) {
        if (!isRunning) return
        val index = stepIndex()
        if (index == spokenStep) return
        spokenStep = index
        if (index >= steps.size) {
            say(finishedMessage)
            stop()
            onFinished()
        } else {
            say(steps[index].spoken)
        }
    }

    private fun say(text: String) {
        val tts = speech ?: return
        if (!speechReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "probe")
    }

    private var finishedMessage = "Finished"

    private fun startSpeech() {
        if (speech != null) return
        speech = TextToSpeech(context) { status ->
            speechReady = status == TextToSpeech.SUCCESS
            // The first step is announced here rather than on the first tick, because the engine
            // takes a moment to come up and the run has already started by then.
            if (speechReady) steps.firstOrNull()?.let { say(it.spoken) }
        }
    }

    fun release() {
        speech?.shutdown()
        speech = null
        speechReady = false
    }

    /** Whether the scan permission is in hand. Requested at the point of use, not at install. */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start(protocol: List<Step>, finished: String): File? {
        if (isRunning) return file
        steps = protocol
        finishedMessage = finished
        spokenStep = 0
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return null
        if (!hasPermission()) return null

        val out = File(context.getExternalFilesDir(null), "rssi-${System.currentTimeMillis()}.csv")
        out.writeText("elapsed_ms,step,address,name,rssi_dbm\n")
        file = out
        startedAt = SystemClock.elapsedRealtime()
        samples = 0
        startSpeech()

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
                out.appendText("$elapsed,${stepIndex()},${result.device.address},${name.replace(',', ' ')},${result.rssi}\n")
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
