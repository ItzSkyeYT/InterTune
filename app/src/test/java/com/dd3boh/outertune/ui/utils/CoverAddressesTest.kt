/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a cover is asked for. A song is stored with the thumbnail of the list it was first seen
 * in, and anything that draws the cover larger than that has to ask the image host for its own
 * size: the home screen widget made its 320 pixel cover out of 120 pixels until it did.
 */
class CoverAddressesTest {

    private val stored = "https://yt3.googleusercontent.com/AbC-dEf_123=w120-h120-l90-rj"
    private fun at(px: Int) = "https://yt3.googleusercontent.com/AbC-dEf_123=w$px-h$px-l90-rj"

    @Test
    fun `the size wanted first, then what the song is stored with`() {
        // the widget's three sizes: a list row, the cover beside a title, the cover on its own
        for (px in listOf(96, 192, 320)) {
            assertEquals("$px", listOf(at(px), stored), coverAddresses(stored, px))
        }
    }

    @Test
    fun `a cover stored larger than wanted is asked for smaller`() {
        // 544 pixels is 37 to 190 kB of download for a 96 pixel list row
        assertEquals(at(96), coverAddresses(at(544), 96).first())
    }

    @Test
    fun `a cover stored at the size wanted is one address, not the same one twice`() {
        assertEquals(listOf(at(192)), coverAddresses(at(192), 192))
    }

    @Test
    fun `an address whose size cannot be changed is tried as it is`() {
        for (other in listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "content://media/external/audio/albumart/12", "")) {
            assertEquals(other, listOf(other), coverAddresses(other, 320))
        }
    }
}
