/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue button at the top of a playlist. Upstream's put every song at the end of the current
 * queue with no sign at all, so people took it for a button that does nothing.
 *
 * A playlist of one's own has none: Add to queue is in its menu, which asks which queue. The
 * automatic lists (Liked, Downloaded and the rest) have no menu, so theirs stays, but it asks which
 * queue as the menus do and then says what happened. This reads the source, as ReleasedFlagsTest
 * does.
 */
class PlaylistHeaderTest {

    private fun screen(name: String) = File("src/main/java/com/dd3boh/outertune/ui/screens/playlist/$name").readText()

    @Test
    fun aPlaylistOfOnesOwnHasNoQueueButton() {
        val text = screen("LocalPlaylistScreen.kt")
        assertFalse("LocalPlaylistScreen still calls enqueueEnd", "enqueueEnd(" in text)
        assertFalse("LocalPlaylistScreen still draws the queue icon", "QueueMusic" in text)
    }

    @Test
    fun anAutomaticListAsksWhichQueueAndSaysWhatItDid() {
        val text = screen("AutoPlaylistScreen.kt")
        assertFalse("AutoPlaylistScreen puts the songs in the queue unasked", "enqueueEnd(" in text)
        assertTrue("AutoPlaylistScreen has no queue button", "Icons.AutoMirrored.Rounded.QueueMusic" in text)
        assertTrue("AutoPlaylistScreen's queue button does not ask which queue", "AddToQueueDialog(" in text)
        for (words in listOf("R.plurals.list_queued_here", "R.plurals.list_queued_other", "R.plurals.list_queued_new")) {
            assertTrue("AutoPlaylistScreen does not say $words", words in text)
        }
        assertTrue("AutoPlaylistScreen does not show what it did", "Toast.makeText(" in text)
    }
}
