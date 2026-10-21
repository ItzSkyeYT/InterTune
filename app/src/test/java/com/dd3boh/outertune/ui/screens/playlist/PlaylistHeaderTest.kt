/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The headers of a playlist and of an automatic playlist (Liked, Downloaded and the rest) have no
 * queue button. Upstream's put every song at the end of the current queue with no sign at all, so
 * people took it for a button that does nothing. Add to queue is in the playlist's menu, which asks
 * which queue. This reads the source, as ReleasedFlagsTest does.
 */
class PlaylistHeaderTest {

    private val screens = listOf("LocalPlaylistScreen.kt", "AutoPlaylistScreen.kt")
        .map { File("src/main/java/com/dd3boh/outertune/ui/screens/playlist/$it") }

    @Test
    fun noHeaderPutsTheWholePlaylistInTheQueueUnasked() {
        for (screen in screens) {
            val text = screen.readText()
            assertFalse("${screen.name} still calls enqueueEnd", "enqueueEnd(" in text)
            assertFalse("${screen.name} still draws the queue icon", "QueueMusic" in text)
        }
    }
}
