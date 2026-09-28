/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A local song tapped on the widget was always sent into a YouTube radio, which fails for a local
 * id. It should play on its own instead, the way Home already plays it.
 */
class WidgetPlaybackKindTest {

    private fun song(isLocal: Boolean, id: String) = MediaMetadata(
        id = id,
        title = "A song",
        artists = listOf(MediaMetadata.Artist(null, "An artist")),
        duration = 180,
        genre = null,
        isLocal = isLocal,
    )

    @Test
    fun `a local song plays on its own, not a radio`() {
        assertEquals(WidgetPlaybackKind.LOCAL, widgetPlaybackKind(song(isLocal = true, id = "LSabcdefgh")))
    }

    @Test
    fun `a YouTube song still keeps the radio`() {
        assertEquals(WidgetPlaybackKind.RADIO, widgetPlaybackKind(song(isLocal = false, id = "FTQbiNvZqaY")))
    }
}
