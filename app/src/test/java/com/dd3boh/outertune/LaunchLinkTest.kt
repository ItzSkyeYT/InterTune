/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.content.Intent
import com.dd3boh.outertune.widget.WidgetCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link MainActivity opens for the intent that started it. Only what it takes from the intent:
 * that the song then plays needs a device.
 */
class LaunchLinkTest {
    private val song = "https://music.youtube.com/watch?v=FTQbiNvZqaY"

    @Test
    fun `a link that started the app is opened`() {
        assertEquals(song, launchLink(Intent.ACTION_VIEW, song, null, fromRecents = false))
    }

    @Test
    fun `a shared link comes as text`() {
        assertEquals(song, launchLink(Intent.ACTION_SEND, null, song, fromRecents = false))
    }

    @Test
    fun `not again from recents or after the activity is restored`() {
        assertNull(launchLink(Intent.ACTION_VIEW, song, null, fromRecents = true))
    }

    @Test
    fun `a widget tap plays its own song`() {
        assertNull(launchLink(WidgetCommands.ACTION_PLAY_SONG, null, null, fromRecents = false))
    }

    @Test
    fun `a plain launch has nothing to open`() {
        assertNull(launchLink(Intent.ACTION_MAIN, null, null, fromRecents = false))
    }
}
