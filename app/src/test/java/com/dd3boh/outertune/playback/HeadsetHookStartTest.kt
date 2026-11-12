/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A wired headset's button, pressed with the app closed, started the player and played nothing.
 *
 * The button is the headset's hook (KEYCODE_HEADSETHOOK). media3's receiver lets it start the
 * service, as it lets play and play/pause, and media3 1.8.0 then has no case for it: a key that
 * comes to the service as a start is decided by MediaSessionImpl.applyMediaButtonKeyEvent, whose
 * cases are play/pause, play, pause, next, previous, forward, rewind and stop. So nothing was
 * resumed, and the service stood there with an empty player: Android closed the app 30 s later
 * (0.12.9, "did not then call Service.startForeground"), or the service gave up after 20 s
 * (0.12.10), and the next press started it again. Seen on the Android 17 emulator with both
 * releases on 12 Nov 2026 (experiments/bugs/issue-153, run 2), where play/pause, sent the same
 * way, resumed the saved queue and played. An AUX plug with three rings in a socket for four
 * shorts what a headset's button shorts, so a car's cable can press this key by itself (#153).
 */
class HeadsetHookStartTest {

    private val receiver = File("src/main/java/com/dd3boh/outertune/playback/ResumingMediaButtonReceiver.kt").readText()

    @Test
    fun `the hook is handed on as play pause, and every other key as it is`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ResumingMediaButtonReceiver.keyHandedOn(KeyEvent.KEYCODE_HEADSETHOOK))
        for (key in listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        )) assertEquals("$key", key, ResumingMediaButtonReceiver.keyHandedOn(key))
    }

    @Test
    fun `media3 is given the key as handed on, the press and its release alike`() {
        val receive = receiver.substringAfter("override fun onReceive(context: Context, intent: Intent?) {").substringBefore("\n    }\n")
        // Whether there is something to resume is asked first, as before; then media3 gets the intent as handed on.
        val asked = receive.indexOf("startService = MusicService.isRunning || hasSavedQueue(context)")
        val handed = receive.indexOf("val handed = handedOn(intent)")
        val given = receive.indexOf("handleIntentAndMaybeStartTheService(context, handed)")
        assertTrue(receive, asked in 0 until handed && handed < given)
        val handedOn = receiver.substringAfter("internal fun handedOn(intent: Intent?): Intent? {").substringBefore("\n        }\n")
        // The same event with another key: its action (the press or the release), its times and its count stay.
        assertTrue(handedOn, "val key = keyHandedOn(event.keyCode)" in handedOn)
        assertTrue(handedOn, "if (key == event.keyCode) return intent" in handedOn)
        assertTrue(handedOn, "KeyEvent(event.downTime, event.eventTime, event.action, key, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)" in handedOn)
        // On a copy: the intent Android sent is not written to.
        assertTrue(handedOn, "return Intent(sent).putExtra(" in handedOn)
        // An intent it cannot read goes on as it came.
        assertTrue(handedOn, "?: return intent" in handedOn)
    }

    @Test
    fun `the log says the key was the hook, since the service sees play pause`() {
        val receive = receiver.substringAfter("override fun onReceive(context: Context, intent: Intent?) {").substringBefore("\n    }\n")
        assertTrue(receive, "hook = handed !== intent" in receive)
        val should = receiver.substringAfter("override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {").substringBefore("\n    }\n")
        // Where media3 asks, which it does for the press only: one line a press.
        assertTrue(should, "if (hook) Log.i(TAG, \"The key is the headset's hook, handed on as play/pause\")" in should)
    }

    @Test
    fun `the hook still counts as a play press for Android's demand`() {
        // The service's look after a media button's start reads the key media3 was given, and both count.
        assertTrue(MediaButtonStart.startsPlayback(KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue(MediaButtonStart.startsPlayback(ResumingMediaButtonReceiver.keyHandedOn(KeyEvent.KEYCODE_HEADSETHOOK)))
    }
}
