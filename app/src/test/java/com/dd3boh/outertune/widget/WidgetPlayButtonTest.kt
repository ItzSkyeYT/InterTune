/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The widget's song when the player's service goes away while paused (Pixel 5, 5 Nov 2026).
 */
class WidgetPlayButtonTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

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
