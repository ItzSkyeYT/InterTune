/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The widget's play button (Pixel 5, 5 Nov 2026): after a tap on next it drew play for about half a
 * second while the song loaded, then pause, because it followed the player's isPlaying.
 */
class WidgetPlayButtonTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `the widget shows pause while a song loads, as the notification and the app's button do`() {
        val update = service.substringAfter("private fun updateWidget(").substringBefore("override fun onDestroy()")
        assertTrue("now(!activePlayer.shouldShowPlayButton())" in update)
        assertFalse("now(activePlayer.isPlaying)" in update)
    }

    @Test
    fun `the widget is redrawn when its button changes, not at every isPlaying flip`() {
        // Every skip flips isPlaying twice (false while the song loads, true once it sounds).
        val flips = service.substringAfter("override fun onIsPlayingChanged(").substringBefore("super.onIsPlayingChanged(")
        assertFalse("updateWidget()" in flips)
        // A pause while a song loads changes playWhenReady alone, so the redraw hangs on onEvents.
        val events = service.substringAfter("override fun onEvents(").substringBefore("val isBufferingOrReady")
        assertTrue("EVENT_PLAY_WHEN_READY_CHANGED" in events)
        assertTrue(
            Regex("""val meantToPlay = !activePlayer\.shouldShowPlayButton\(\)\s*if \(meantToPlay != widgetMeantToPlay\) \{\s*widgetMeantToPlay = meantToPlay\s*updateWidget\(\)""")
                .containsMatchIn(events)
        )
    }

    @Test
    fun `a service going away while paused leaves the widget its song`() {
        // Pixel 5, 5 Nov 2026: home pressed while paused, the service ended, and the widget said
        // "Nothing playing" over the queue its play button resumes. The song is read at the call,
        // before deInitQueue empties the board, not inside the write, which waits for a lock.
        val update = service.substringAfter("private fun updateWidget(").substringBefore("override fun onDestroy()")
        val atCall = update.substringBefore("lastingScope.launch")
        assertTrue("val kept = if (!stopped || !dataStore.get(PersistentQueueKey, true)) null else {" in atCall)
        // A service that has played nothing since it started has no song in the player.
        assertTrue("?: queueBoard.takeIf { qbInit.value }?.getCurrentQueue()?.let { q -> q.queue.getOrNull(q.queuePos) }" in atCall)
        assertTrue("if (stopped) kept ?: WidgetStore.NowState(null, false)" in update)
        val destroy = service.substringAfter("override fun onDestroy()")
        assertTrue("written before the queue is put away", destroy.indexOf("updateWidget(stopped = true)") < destroy.indexOf("deInitQueue()"))
    }
}
