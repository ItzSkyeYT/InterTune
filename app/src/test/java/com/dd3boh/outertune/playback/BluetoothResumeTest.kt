/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Resume when Bluetooth connects: music that stopped because its output went away plays again
 * when Bluetooth audio connects, and music somebody stopped never does.
 */
class BluetoothResumeTest {

    private val minute = 60_000L
    private val noisy = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
    private val byHand = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    /** The same thing as a car or headphones: an A2DP output with its id. */
    private val car = listOf(41)

    /** Music playing through [car] since 0. */
    private fun playing(): BluetoothResume = BluetoothResume().apply {
        outputsThere(car)
        playWhenReadyChanged(true, byHand, now = 0L)
    }

    private fun BluetoothResume.resumes(
        now: Long,
        enabled: Boolean = true,
        playWhenReady: Boolean = false,
        state: Int = Player.STATE_READY,
        hasSong: Boolean = true,
    ) = shouldResume(enabled, now, playWhenReady, state, hasSong)

    @Test
    fun `music its output stopped plays again when Bluetooth connects`() {
        val r = playing()
        r.playWhenReadyChanged(false, noisy, now = 10_000L)
        r.outputsRemoved(car, now = 10_900L)
        assertTrue(r.outputsAdded(listOf(57)))
        assertTrue(r.resumes(now = 10_000L + 12 * minute))
    }

    @Test
    fun `music paused by hand stays paused, also when the headphones then go`() {
        val r = playing()
        r.playWhenReadyChanged(false, byHand, now = 10_000L)
        // media3 1.8.0 says becoming noisy again for music already paused, as the reason changes.
        r.playWhenReadyChanged(false, noisy, now = 10_000L + minute)
        r.outputsRemoved(car, now = 10_000L + minute + 900L)
        r.outputsAdded(listOf(57))
        assertFalse(r.resumes(now = 10_000L + 5 * minute))
    }

    @Test
    fun `a pause the car sends as it goes counts as the output going`() {
        val r = playing()
        r.playWhenReadyChanged(false, byHand, now = 10_000L)
        r.playWhenReadyChanged(false, noisy, now = 10_400L)
        r.outputsRemoved(car, now = 11_200L)
        r.outputsAdded(listOf(57))
        assertTrue(r.resumes(now = 10_000L + 20 * minute))
    }

    @Test
    fun `a pause more than two seconds before the output goes is somebody's own`() {
        val r = playing()
        r.playWhenReadyChanged(false, byHand, now = 10_000L)
        r.outputsRemoved(car, now = 10_000L + BluetoothResume.GOING_MS + 1)
        r.outputsAdded(listOf(57))
        assertFalse(r.resumes(now = 10_000L + minute))
    }

    @Test
    fun `a pause by the sleep timer at the end of a song is not the output going`() {
        val r = playing()
        r.playWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, now = 10_000L)
        r.outputsRemoved(car, now = 10_500L)
        r.outputsAdded(listOf(57))
        assertFalse(r.resumes(now = 10_000L + minute))
    }

    @Test
    fun `only within thirty minutes of the stop`() {
        val r = playing()
        r.playWhenReadyChanged(false, noisy, now = 10_000L)
        assertTrue(r.resumes(now = 10_000L + BluetoothResume.RESUME_WITHIN_MS))
        assertFalse(r.resumes(now = 10_000L + BluetoothResume.RESUME_WITHIN_MS + 1))
    }

    @Test
    fun `playing again forgets the stop, and so does a pause by anything else`() {
        val again = playing()
        again.playWhenReadyChanged(false, noisy, now = 10_000L)
        again.playWhenReadyChanged(true, byHand, now = 20_000L)
        again.playWhenReadyChanged(false, byHand, now = 30_000L)
        assertFalse(again.resumes(now = 40_000L))

        // Pause pressed on music already stopped, or the app pausing on an error.
        val pausedOver = playing()
        pausedOver.playWhenReadyChanged(false, noisy, now = 10_000L)
        pausedOver.playWhenReadyChanged(false, byHand, now = 20_000L)
        assertFalse(pausedOver.resumes(now = 30_000L))

        val focus = playing()
        focus.playWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, now = 10_000L)
        assertFalse(focus.resumes(now = 20_000L))
    }

    @Test
    fun `with the setting off it never resumes`() {
        val r = playing()
        r.playWhenReadyChanged(false, noisy, now = 10_000L)
        assertFalse(r.resumes(now = 20_000L, enabled = false))
    }

    @Test
    fun `only a player that still holds a song and is ready to play it`() {
        val r = playing()
        r.playWhenReadyChanged(false, noisy, now = 10_000L)
        assertTrue(r.resumes(now = 20_000L, state = Player.STATE_BUFFERING))
        assertFalse(r.resumes(now = 20_000L, state = Player.STATE_IDLE))
        assertFalse(r.resumes(now = 20_000L, state = Player.STATE_ENDED))
        assertFalse(r.resumes(now = 20_000L, hasSong = false))
        assertFalse(r.resumes(now = 20_000L, playWhenReady = true))
    }

    @Test
    fun `never before anything has stopped`() {
        assertFalse(BluetoothResume().resumes(now = 0L))
        assertFalse(playing().resumes(now = minute))
    }

    @Test
    fun `the outputs there when the watch begins are not arrivals`() {
        val r = BluetoothResume()
        r.outputsThere(listOf(5))
        assertTrue(r.anyOutput)
        assertFalse(r.outputsAdded(listOf(5)))
        assertTrue(r.outputsAdded(listOf(5, 9)))
        assertFalse(r.outputsAdded(listOf(9)))
        r.outputsRemoved(listOf(5, 9), now = 0L)
        assertFalse(r.anyOutput)
        assertTrue("one that comes back is an arrival", r.outputsAdded(listOf(9)))
    }

    @Test
    fun `an output that was never seen going does not make a pause the device's own`() {
        val r = playing()
        r.playWhenReadyChanged(false, byHand, now = 10_000L)
        r.outputsRemoved(listOf(77), now = 10_500L)
        assertFalse(r.resumes(now = minute))
    }

    @Test
    fun `only Bluetooth outputs music plays through count`() {
        val s = Build.VERSION_CODES.S
        assertTrue(BluetoothResume.isMusicOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isSink = true, sdkInt = Build.VERSION_CODES.N))
        assertTrue(BluetoothResume.isMusicOutput(AudioDeviceInfo.TYPE_BLE_HEADSET, isSink = true, sdkInt = s))
        assertTrue(BluetoothResume.isMusicOutput(AudioDeviceInfo.TYPE_BLE_SPEAKER, isSink = true, sdkInt = s))
        assertFalse("LE Audio types begin at Android 12", BluetoothResume.isMusicOutput(AudioDeviceInfo.TYPE_BLE_HEADSET, isSink = true, sdkInt = s - 1))
        assertFalse("a headset's microphone", BluetoothResume.isMusicOutput(AudioDeviceInfo.TYPE_BLE_HEADSET, isSink = false, sdkInt = s))
        assertFalse("the link for calls", BluetoothResume.isMusicOutput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, isSink = true, sdkInt = s))
        for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)) {
            assertFalse(BluetoothResume.isMusicOutput(type, isSink = true, sdkInt = s))
        }
    }

    @Test
    fun `the switch and the service take it for off where nothing was chosen, and the service lets the watch go`() {
        // Read from the source, as AutoSettingsTest does: a JVM test cannot run the service.
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val settings = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/PlayerFrag.kt").readText()
        assertTrue("it[ResumeOnBluetoothKey] ?: false" in service)
        assertTrue(Regex("key = ResumeOnBluetoothKey,\\s+defaultValue = false").containsMatchIn(settings))
        val teardown = service.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertTrue("onDestroy unregisters the device callback", "unregisterAudioDeviceCallback(bluetoothOutputs)" in teardown)
        assertTrue("the player's reasons reach it", "bluetoothResume.playWhenReadyChanged(playWhenReady, reason" in service)
    }
}
