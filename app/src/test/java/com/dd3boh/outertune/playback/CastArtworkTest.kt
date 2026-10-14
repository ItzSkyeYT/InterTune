/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastArtworkTest {
    @Test
    fun `a list's small thumbnail is asked for at a TV's size`() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            castArtwork("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            castArtwork("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"),
        )
    }

    @Test
    fun `an address the image host cannot size goes as it is, and a file on the phone not at all`() {
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", castArtwork("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg"))
        assertNull(castArtwork("/storage/emulated/0/Music/cover.jpg"))
        assertNull(castArtwork(null))
    }
}
