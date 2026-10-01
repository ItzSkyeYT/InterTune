/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.content.Intent
import android.provider.MediaStore
import com.dd3boh.outertune.widget.WidgetCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link MainActivity holds for an intent that reaches onNewIntent: one tapped or shared while
 * the app is open, or on a task whose process died, which Android delivers there before the first
 * composition. Only what it takes from the intent: that it then opens needs a device.
 */
class NewIntentLinkTest {
    private val song = "https://music.youtube.com/watch?v=FTQbiNvZqaY"

    @Test
    fun `a tapped link is held`() {
        assertEquals(song, newIntentLink(Intent.ACTION_VIEW, song, null))
    }

    @Test
    fun `a shared link comes as text`() {
        assertEquals(song, newIntentLink(Intent.ACTION_SEND, null, song))
    }

    @Test
    fun `a link wins over shared text`() {
        assertEquals(song, newIntentLink(Intent.ACTION_VIEW, song, "some words"))
    }

    @Test
    fun `a play request that came in through a link filter opens the link`() {
        assertEquals(song, newIntentLink(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, song, null))
    }

    @Test
    fun `a widget tap's own uri is not a link`() {
        assertNull(newIntentLink(WidgetCommands.ACTION_PLAY_SONG, "intertune://widget/song/FTQbiNvZqaY", null))
    }

    @Test
    fun `shortcuts, notifications and plain launches carry no link`() {
        assertNull(newIntentLink(MainActivity.ACTION_PLAY_LIKED, null, null))
        assertNull(newIntentLink(MainActivity.ACTION_SONGS, null, null))
        assertNull(newIntentLink(null, null, null))
        assertNull(newIntentLink(Intent.ACTION_MAIN, null, null))
    }
}
