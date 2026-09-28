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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
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

    /** When the scan last stopped, so a restart moments later can keep what it had learned. */
    private var stoppedAt = 0L

    /** Whether this scan has heard the headphones yet; see [widenIfSilent]. */
    @Volatile private var heard = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The name filters match exactly, and headphones that advertise under some other pattern than
     * the two tried (a suffix, another prefix) would never be heard at all. So a filtered scan that
     * hears nothing within [WIDEN_AFTER_MS] is swapped for the unfiltered one this used before: it
     * pauses with the screen off, but it works with the screen on, as it always did.
     */
    // On the property, as on startInternal and stop: on the local below it covered reading the
    // scanner and not the two calls after it, which lint then failed the build over.
    @SuppressLint("MissingPermission")
    private val widenIfSilent = Runnable {
        if (!running || heard || !hasPermission()) return@Runnable
        runCatching {
            val scanner = adapter?.bluetoothLeScanner ?: return@runCatching
            scanner.stopScan(callback)
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setReportDelay(0).build(), callback)
            Log.i(TAG, "nothing heard under the paired name, scanning without a filter")
        }.onFailure { Log.w(TAG, "could not widen the scan: ${it.message}") }
    }

    val isAvailable: Boolean
        get() = adapter?.isEnabled == true && hasPermission()

    fun hasPermission(): Boolean {
        // Android 12 and up only. Below that a scan needs BLUETOOTH, BLUETOOTH_ADMIN and location,
        // none of which the manifest asks for, so it failed quietly, and reading the adapter's
        // state threw SecurityException outright.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun connectedHeadphoneName(): String? {
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return null
        val outputs = runCatching {
            audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return null
        return outputs.firstOrNull { isHeadphoneOutputType(it.type) }
            ?.productName?.toString()?.takeIf { it.isNotBlank() }
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
        val target = connectedHeadphoneName() ?: return false

        // Starts and stops with play and pause (see shouldScan). Starting from nothing each time
        // took the first reading after a resume as "near", so pausing on the far side of a room
        // and resuming there played at full volume. The same headphones back within two minutes
        // keep the readings and the reference.
        val now = SystemClock.elapsedRealtime()
        if (target != targetName || now - stoppedAt > RESUME_WINDOW_MS) {
            times.clear()
            values.clear()
            nearReference = Float.NEGATIVE_INFINITY
        }
        targetName = target
        lastDecayAt = now
        running = true

        // Balanced rather than low latency: ten seconds of median needs a reading a second, not
        // ten, and the radio is on someone's head for hours at a time.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()
        // Filtered, because Android pauses an unfiltered scan while the screen is off, which is
        // exactly when this is needed: the phone on the table, the listener walking away. The two
        // names are the one it pairs under and the one it advertises under ("WH-1000XM5" and
        // "LE_WH-1000XM5"); the callback still checks by containment.
        val filters = listOf(target, "LE_$target").map { ScanFilter.Builder().setDeviceName(it).build() }
        heard = false
        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { Log.w(TAG, "scan refused: ${it.message}"); running = false }
        if (running) {
            handler.removeCallbacks(widenIfSilent)
            handler.postDelayed(widenIfSilent, WIDEN_AFTER_MS)
        }
        return running
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        stoppedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(widenIfSilent)
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
            heard = true
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

        /** How long a stopped scan keeps its readings for a restart with the same headphones. */
        const val RESUME_WINDOW_MS = 120_000L

        /** How long a filtered scan may hear nothing before it is widened. */
        const val WIDEN_AFTER_MS = 15_000L

        /**
         * Whether an AudioManager output device type counts as the Bluetooth headphones this
         * feature watches. LE Audio routes report under their own distinct type, not
         * TYPE_BLUETOOTH_A2DP, so it needs its own branch; mirrors AudioRoute.playbackIsPrivate,
         * which treats the two the same way under the same SDK gate hasPermission() already
         * requires for BLUETOOTH_SCAN.
         */
        // TYPE_BLE_HEADSET is a compile-time constant that is only compared, so reading it below S
        // does no harm.
        @SuppressLint("InlinedApi")
        fun isHeadphoneOutputType(type: Int, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
            type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (sdkInt >= Build.VERSION_CODES.S && type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)

        /**
         * Whether the scan should run: the setting is on and the player means to play.
         *
         * Keyed on playWhenReady and the state rather than on isPlaying. A seek or a skip masks
         * READY to BUFFERING, and a rebuffer drops isPlaying, although nobody stopped the music. A
         * transient focus loss (a call, a navigation prompt) only suppresses playback and leaves
         * playWhenReady set. So the scan carries on through all of them, and a pause (buffering or
         * not), the end of the queue and an error that leaves the player idle stop it.
         */
        fun shouldScan(enabled: Boolean, playWhenReady: Boolean, playbackState: Int): Boolean =
            enabled && playWhenReady &&
                (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)
    }
}
