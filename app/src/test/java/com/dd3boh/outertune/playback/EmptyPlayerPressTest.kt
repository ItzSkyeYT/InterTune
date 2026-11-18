/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play/pause on a headset, a speaker or a car did nothing while the app was open and had played nothing.
 *
 * After a cold start the player is empty until something loads the saved queue into it. A key from
 * Bluetooth or a wired headset reaches the running service through Android's own session, where media3
 * 1.8.0 takes play/pause and the hook as a toggle of the player as it is
 * (MediaSessionLegacyStub.handleMediaPlayPauseOnHandler): no saved queue is asked for, and an empty player
 * stays empty. Play goes another way in media3 and resumes. The emulator showed it on 12 Nov 2026 with a
 * running service and nothing loaded: four hook presses toggled an empty player (experiments/bugs/issue-153,
 * run 1). Play/pause and the hook now load the saved queue and play it, as the app's own play button does.
 */
class EmptyPlayerPressTest {

    private val callback = File("src/main/java/com/dd3boh/outertune/playback/MediaLibrarySessionCallback.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    private fun plays(key: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, legacy: Boolean = true, empty: Boolean = true) =
        EmptyPlayerPress.playsSavedQueue(key, action, repeat, legacy, empty)

    /** [since] ms after a press played the saved queue (null: none did), with Android's double tap time, 300 ms. */
    private fun second(key: Int, since: Long?, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, legacy: Boolean = true) =
        EmptyPlayerPress.secondOfDoublePress(key, action, repeat, legacy, since, doubleTapMs = 300)

    @Test
    fun `a second press within the double tap time of the one that played the saved queue is taken with it`() {
        // A wired headset's double click comes about 150 to 250 ms apart. Left to media3, the second press
        // was a single one: 300 ms later it paused the song the first had just started.
        assertTrue(second(KeyEvent.KEYCODE_HEADSETHOOK, since = 180))
        assertTrue(second(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, since = 0))
        assertTrue(second(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, since = 300))
    }

    @Test
    fun `later, or with no such press before it, the press is media3's as before`() {
        assertFalse(second(KeyEvent.KEYCODE_HEADSETHOOK, since = 301))
        assertFalse(second(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, since = 5_000))
        assertFalse(second(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, since = null))
        assertFalse(second(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, since = -1))
    }

    @Test
    fun `only what media3 counts for a double press is taken as the second one`() {
        // Pressed, not released or held, through Android's own session, and play/pause or the hook.
        assertFalse(second(KeyEvent.KEYCODE_HEADSETHOOK, since = 180, action = KeyEvent.ACTION_UP))
        assertFalse(second(KeyEvent.KEYCODE_HEADSETHOOK, since = 180, repeat = 1))
        assertFalse(second(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, since = 180, legacy = false))
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            assertFalse("$key", second(key, since = 180))
        }
    }

    @Test
    fun `the session takes the second press before media3 or the empty player's rule sees it, and only once`() {
        val button = callback.substringAfter("override fun onMediaButtonEvent(").substringBefore("\n    }\n")
        assertTrue(button, "val now = SystemClock.elapsedRealtime()" in button)
        val taken = button.indexOf("EmptyPlayerPress.secondOfDoublePress(key.keyCode, key.action, key.repeatCount, legacy,")
        assertTrue(button, "sinceLoadMs = savedQueuePressAt?.let { now - it }, doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong())" in button)
        val rule = button.indexOf("EmptyPlayerPress.playsSavedQueue(")
        assertTrue(button, taken in 0 until rule)
        // Taken: the pair is over, so a third press is media3's; and media3 is told the press was handled.
        val over = button.indexOf("savedQueuePressAt = null", taken)
        assertTrue(button, over in taken until rule && button.indexOf("return true", over) in over until rule)
        // The press that plays the saved queue is the one remembered.
        val kept = button.indexOf("savedQueuePressAt = now")
        assertTrue(button, kept in rule until button.indexOf("service.playSavedQueue("))
    }

    @Test
    fun `play pause and the headset's button on an empty player play the saved queue`() {
        assertTrue(plays(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(plays(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    @Test
    fun `with a song in the player the key goes on to media3 as before`() {
        // Paused on a song, play/pause plays it and the hook keeps its double tap for next: media3's own.
        assertFalse(plays(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, empty = false))
        assertFalse(plays(KeyEvent.KEYCODE_HEADSETHOOK, empty = false))
    }

    @Test
    fun `a key that came as a start of the service is left to media3, which resumes it already`() {
        // The widget, the notification and a press with the app closed come with media3's own controller.
        assertFalse(plays(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, legacy = false))
        assertFalse(plays(KeyEvent.KEYCODE_HEADSETHOOK, legacy = false))
    }

    @Test
    fun `only the first press counts, never its release or a key held down`() {
        assertFalse(plays(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, action = KeyEvent.ACTION_UP))
        // A held hook is Android's voice assistant, not play.
        assertFalse(plays(KeyEvent.KEYCODE_HEADSETHOOK, repeat = 1))
    }

    @Test
    fun `every other key is media3's`() {
        // Play resumes in media3 already; pause, next and the rest have nothing to do on an empty player.
        for (key in listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_VOLUME_UP,
        )) assertFalse("$key", plays(key))
    }

    @Test
    fun `the session hands such a press to the service and keeps it from media3`() {
        val button = callback.substringAfter("override fun onMediaButtonEvent(").substringBefore("\n    }\n")
        // The key read from the intent; an intent without one goes on to media3.
        assertTrue(button, "IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)" in button)
        assertTrue(button, "?: return false" in button)
        // Only what came through Android's own session, and only with nothing in the session's player.
        assertTrue(button, "controllerInfo.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION" in button)
        assertTrue(button, "EmptyPlayerPress.playsSavedQueue(key.keyCode, key.action, key.repeatCount, legacy, empty = service.player.currentMediaItem == null)" in button)
        // The service plays it, and media3 is told the press was handled: it would toggle the empty player again.
        val asked = button.indexOf("service.playSavedQueue(")
        assertTrue(button, asked > 0)
        assertTrue(button, button.indexOf("return true", asked) > asked)
    }

    @Test
    fun `the service plays the saved queue as the app's own play button does`() {
        val play = service.substringAfter("fun playSavedQueue(what: String) {").substringBefore("\n    }\n")
        // On the main thread, once the saved queues are read (bounded), and with the board read again after a swipe.
        assertTrue(play, play.trimStart().startsWith("scope.launch {"))
        val waited = play.indexOf("withTimeoutOrNull(SAVED_QUEUES_WAIT_MS) { queuesLoadedOnce.await() }")
        val read = play.indexOf("if (!qbInit.value) initQueue(onlyIfNeeded = true)")
        // Loaded only into an empty player: a song loaded meanwhile plays as it is.
        val loaded = play.indexOf("if (activePlayer.mediaItemCount == 0) {")
        val board = play.indexOf("queueBoard.setCurrQueue(shouldResume = true)")
        // Prepared when idle, then played: never a toggle, which would pause a song a tap had started meanwhile.
        val played = play.indexOf("activePlayer.playSafe()")
        assertTrue(play, waited in 0 until read && read < loaded && loaded < board && board < played)
        // With nothing saved it says so and plays nothing.
        assertTrue(play, "no saved queue to play" in play)
        assertFalse(play, "togglePlayPause" in play)
    }
}
