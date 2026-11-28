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
 * Play at the very end of a queue that has ended started nothing (tester of 28 Nov 2026, Pixel 5): on the widget, the
 * press paused the ended player (media3 1.8.0 reads its play/pause by playWhenReady, still true at the end), and with
 * the app restarted the saved queue came back at the end of its last song, so the widget's Play and the mini player's
 * first tap played its last moment and stopped. The app's own Play on a queue that ends while it runs starts it again
 * from its first song (playSafe), and every Play here does that now.
 */
class EndedQueuePressTest {

    private val dir = File("src/main/java/com/dd3boh/outertune/playback")
    private fun source(name: String) = File(dir, name).readText()

    private fun startsOver(
        key: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeat: Int = 0,
        legacy: Boolean = false,
        ended: Boolean = true,
        listens: Boolean = false,
    ) = EndedQueuePress.startsOver(key, action, repeat, legacy, ended, listens)

    @Test
    fun `play from the widget or the notification on an ended queue starts it again`() {
        assertTrue(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    @Test
    fun `anything else goes on to media3 as before`() {
        // Not ended: media3 plays or pauses as ever.
        assertFalse(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ended = false))
        // Other keys, a release, a key held.
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP)) {
            assertFalse("$key", startsOver(key))
        }
        assertFalse(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, action = KeyEvent.ACTION_UP))
        assertFalse(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeat = 1))
        // Through Android's own session (a headset, a car): media3 waits out a double press for it.
        assertFalse(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, legacy = true))
        // A guest who only listens in a room: the button is a mute there.
        assertFalse(startsOver(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, listens = true))
    }

    @Test
    fun `the session starts the ended queue as the app's play button does, before media3 sees the press`() {
        val callback = source("MediaLibrarySessionCallback.kt")
        val button = callback.substringAfter("override fun onMediaButtonEvent(").substringBefore("\n    }\n")
        val rule = button.indexOf("if (EndedQueuePress.startsOver(key.keyCode, key.action, key.repeatCount, legacy,")
        assertTrue(button, rule > button.indexOf("if (service.resumptionRunning) return false"))
        assertTrue(button, rule < button.indexOf("EmptyPlayerPress.secondOfDoublePress("))
        assertTrue(button, "ended = player.playbackState == Player.STATE_ENDED && player.mediaItemCount > 0," in button)
        assertTrue(button, "listensInRoom = service.together.value?.listens == true)) {" in button)
        val play = button.indexOf("player.playSafe()", rule)
        assertTrue(button, play > rule && button.indexOf("return true", play) > play)
        assertTrue(button, "val player = session.player" in button)
    }

    @Test
    fun `a saved queue that had ended plays from its first song, from the widget, the mini player and a headset`() {
        // The widget with the app closed or nothing loaded: media3's resumption.
        val callback = source("MediaLibrarySessionCallback.kt")
        val resumption = callback.substringAfter("private suspend fun resumptionItems(): MediaItemsWithStartPosition? {").substringBefore("\n    }\n")
        assertTrue(resumption, "ResumePoint.playFrom(at, songs.size, q.lastSongPos, songs.getOrNull(at)?.duration ?: 0)" in resumption)
        assertTrue(resumption, "MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, index, positionMs)" in resumption)
        // The app's play buttons after a restart, before the saved queue goes into the empty player.
        val connection = source("PlayerConnection.kt")
        val toggle = connection.substringAfter("fun togglePlayPause() {").substringBefore("\n    }\n")
        val over = toggle.indexOf("if (player.currentMediaItem == null) service.queueBoard.startOverIfEnded()")
        assertTrue(toggle, over >= 0 && over < toggle.indexOf("loadQueueIfEmpty().togglePlayPause()"))
        // A headset's play/pause on an empty player (EmptyPlayerPress).
        val saved = source("MusicService.kt").substringAfter("fun playSavedQueue(what: String) {").substringBefore("\n    }\n")
        val first = saved.indexOf("queueBoard.startOverIfEnded()")
        assertTrue(saved, first >= 0 && first < saved.indexOf("queueBoard.setCurrQueue(shouldResume = true)"))
        // The board moves the queue to its first song by the same rule, and keeps it when it had not ended.
        val board = source("QueueBoard.kt").substringAfter("fun startOverIfEnded(): Boolean {").substringBefore("\n    }\n")
        assertTrue(board, "ResumePoint.playFrom(at, songs.size, q.lastSongPos, songs.getOrNull(at)?.duration ?: 0)" in board)
        assertTrue(board, "if (start == at to q.lastSongPos) return false" in board)
        assertTrue(board, "q.setCurrentQueuePos(start.first)" in board && "q.lastSongPos = start.second" in board)
    }
}
