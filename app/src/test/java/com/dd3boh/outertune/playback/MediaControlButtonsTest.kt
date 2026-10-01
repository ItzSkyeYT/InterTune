/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.MediaControlButton.LIBRARY
import com.dd3boh.outertune.playback.MediaControlButton.LIKE
import com.dd3boh.outertune.playback.MediaControlButton.RADIO
import com.dd3boh.outertune.playback.MediaControlButton.REPEAT
import com.dd3boh.outertune.playback.MediaControlButton.SHUFFLE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which buttons the phone's media controls get, in what order, and how the setting is read. */
class MediaControlButtonsTest {

    @Test
    fun `nothing set sends the layout the app always sent`() {
        // Shuffle, repeat, like, radio: the order updateNotification hard coded before the
        // setting existed. Anyone who updates and never opens the setting sees no change.
        assertEquals(listOf(SHUFFLE, REPEAT, LIKE, RADIO), MediaControlButtons.layout(MediaControlButtons.parse(null)))
        assertEquals(listOf(SHUFFLE, REPEAT, LIKE, RADIO), MediaControlButtons.layout(MediaControlButtons.parse("")))
        assertEquals(listOf(SHUFFLE, REPEAT), MediaControlButtons.DEFAULT)
    }

    @Test
    fun `the two picked come first, in the order picked`() {
        assertEquals(listOf(LIKE, RADIO, SHUFFLE, REPEAT), MediaControlButtons.layout(listOf(LIKE, RADIO)))
        assertEquals(listOf(RADIO, LIKE, SHUFFLE, REPEAT), MediaControlButtons.layout(listOf(RADIO, LIKE)))
        assertEquals(listOf(REPEAT, SHUFFLE, LIKE, RADIO), MediaControlButtons.layout(listOf(REPEAT, SHUFFLE)))
        assertEquals(listOf(LIKE, SHUFFLE, REPEAT, RADIO), MediaControlButtons.layout(listOf(LIKE, SHUFFLE)))
    }

    @Test
    fun `everything not picked still follows, so controllers with more room keep it`() {
        for (first in MediaControlButton.entries) for (second in MediaControlButton.entries) {
            if (first == second) continue
            val layout = MediaControlButtons.layout(listOf(first, second))
            assertEquals(listOf(first, second), layout.take(2))
            assertEquals("no button twice in $layout", layout.distinct(), layout)
            for (button in listOf(SHUFFLE, REPEAT, LIKE, RADIO)) {
                assertEquals("$button missing from $layout", true, button in layout)
            }
        }
    }

    @Test
    fun `add to library only joins the layout when it is picked`() {
        // It was never in the layout, so leaving it out of the rest keeps Android Auto as it was.
        assertEquals(false, LIBRARY in MediaControlButtons.layout(listOf(LIKE, RADIO)))
        assertEquals(listOf(LIBRARY, LIKE, SHUFFLE, REPEAT, RADIO), MediaControlButtons.layout(listOf(LIBRARY, LIKE)))
        assertEquals(listOf(SHUFFLE, LIBRARY, REPEAT, LIKE, RADIO), MediaControlButtons.layout(listOf(SHUFFLE, LIBRARY)))
    }

    @Test
    fun `the setting survives being written and read back`() {
        for (first in MediaControlButton.entries) for (second in MediaControlButton.entries) {
            if (first == second) continue
            val pair = listOf(first, second)
            assertEquals(pair, MediaControlButtons.parse(MediaControlButtons.serialize(pair)))
        }
        assertEquals("LIKE,RADIO", MediaControlButtons.serialize(listOf(LIKE, RADIO)))
    }

    @Test
    fun `a value it cannot fully read still gives two different buttons`() {
        // A name from a later version, or a backup edited by hand.
        assertEquals(listOf(LIKE, SHUFFLE), MediaControlButtons.parse("LIKE,SOMETHING_NEW"))
        assertEquals(listOf(LIKE, SHUFFLE), MediaControlButtons.parse("SOMETHING_NEW,LIKE"))
        assertEquals(listOf(SHUFFLE, REPEAT), MediaControlButtons.parse("SHUFFLE"))
        assertEquals(listOf(REPEAT, SHUFFLE), MediaControlButtons.parse("REPEAT"))
        assertEquals(listOf(SHUFFLE, REPEAT), MediaControlButtons.parse("nonsense"))
        assertEquals(listOf(LIKE, SHUFFLE), MediaControlButtons.parse("LIKE,LIKE"))
        assertEquals(listOf(RADIO, LIKE), MediaControlButtons.parse(" RADIO , LIKE ,SHUFFLE"))
        assertEquals(listOf(SHUFFLE, REPEAT), MediaControlButtons.parse(",,"))
    }

    @Test
    fun `the layout never trusts its input to be a clean pair`() {
        assertEquals(listOf(LIKE, SHUFFLE, REPEAT, RADIO), MediaControlButtons.layout(listOf(LIKE, LIKE)))
        assertEquals(listOf(RADIO, LIKE, SHUFFLE, REPEAT), MediaControlButtons.layout(listOf(RADIO, LIKE, SHUFFLE)))
        assertEquals(listOf(SHUFFLE, REPEAT, LIKE, RADIO), MediaControlButtons.layout(emptyList()))
    }

    @Test
    fun `picking in the dialog fills the slots in order and a second tap takes one back`() {
        var picked = emptyList<MediaControlButton>()
        picked = MediaControlButtons.toggle(picked, RADIO)
        assertEquals(listOf(RADIO), picked)
        picked = MediaControlButtons.toggle(picked, LIKE)
        assertEquals(listOf(RADIO, LIKE), picked)
        // Both slots full: a third does nothing until one is taken back.
        assertEquals(listOf(RADIO, LIKE), MediaControlButtons.toggle(picked, SHUFFLE))
        // Taking back the first moves the second up.
        picked = MediaControlButtons.toggle(picked, RADIO)
        assertEquals(listOf(LIKE), picked)
        picked = MediaControlButtons.toggle(picked, LIBRARY)
        assertEquals(listOf(LIKE, LIBRARY), picked)
        picked = MediaControlButtons.toggle(picked, LIBRARY)
        assertEquals(listOf(LIKE), picked)
    }
}
