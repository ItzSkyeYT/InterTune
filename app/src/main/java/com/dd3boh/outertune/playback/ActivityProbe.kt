/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import java.io.File
import kotlin.math.sqrt

/**
 * Records how much the listener was moving while each song played.
 *
 * To answer one question before anything is built on it: does knowing somebody is walking tell the
 * engine anything the clock does not already tell it? The engine already scores candidates against
 * a time-of-day bucket, and activity is plausibly just a noisier way of asking the same thing.
 * Walking at half past eight is the commute; still at eleven is winding down. If that is all it
 * is, wiring it in would cost a sensor subscription and buy nothing.
 *
 * Answerable from a listener's own history, which is why this writes a file rather than changing
 * how anything is chosen. One row per minute of playback: what was on, and how much the phone was
 * moving. Joined against the listen log afterwards it gives skip rates and tag mixes per activity
 * within each time bucket, and those either differ or they do not.
 *
 * The accelerometer rather than the step counter, deliberately. Step counting needs
 * ACTIVITY_RECOGNITION, and asking for a sensitive permission to run an experiment that may well
 * conclude "not worth it" is the wrong way round. The accelerometer needs nothing, is on every
 * phone, and does not depend on Play Services, which matters because the F-Droid build cannot have
 * it. If the answer comes back yes, that is the point to consider a better sensor.
 *
 * The number is logged, not just the verdict. Thresholds picked in advance are exactly the kind of
 * thing that turns out wrong, and a recorded standard deviation can be reclassified afterwards
 * where a recorded label cannot.
 */
class ActivityProbe(
    private val context: Context,
    private val handler: Handler,
    private val nowPlaying: () -> String?,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private var file: File? = null

    private val window = ArrayDeque<Float>()
    private var running = false

    val isRunning: Boolean
        get() = running

    fun start(): File? {
        if (running) return file
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val out = File(context.getExternalFilesDir(null), "activity.csv")
        if (!out.exists()) out.writeText("epoch_ms,song_id,spread_ms2,samples\n")
        file = out
        running = true
        window.clear()
        // Normal delay is about five samples a second, which is plenty to tell a pocket on a walk
        // from a table, and is the cheapest rate the platform offers.
        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, handler)
        handler.postDelayed(tick, INTERVAL_MS)
        return out
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { sensors?.unregisterListener(this) }
        handler.removeCallbacks(tick)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Magnitude, so which way up the phone is sitting does not matter. Gravity is a constant
        // offset in it, which the spread below removes anyway.
        window.addLast(sqrt(x * x + y * y + z * z))
        while (window.size > MAX_SAMPLES) window.removeFirst()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val song = nowPlaying()
            val n = window.size
            if (song != null && n >= MIN_SAMPLES) {
                val mean = window.sum() / n
                var variance = 0.0
                for (v in window) variance += (v - mean).toDouble() * (v - mean)
                val spread = sqrt(variance / n)
                runCatching {
                    file?.appendText("${System.currentTimeMillis()},$song,${"%.4f".format(spread)},$n\n")
                }
            }
            window.clear()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "ActivityProbe"

        /** One row a minute. Fine enough to separate a walk from sitting, coarse enough to ignore. */
        const val INTERVAL_MS = 60_000L

        /** A minute at the normal rate is around three hundred samples; keep the last two minutes. */
        const val MAX_SAMPLES = 600
        const val MIN_SAMPLES = 20
    }
}
