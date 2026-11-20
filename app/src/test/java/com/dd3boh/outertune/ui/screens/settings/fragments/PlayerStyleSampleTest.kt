/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.ui.player.PlayerPin
import com.dd3boh.outertune.ui.player.PlayerPins
import com.dd3boh.outertune.ui.screens.settings.fragments.SampleButton.Menu
import com.dd3boh.outertune.ui.screens.settings.fragments.SampleButton.Pin
import com.dd3boh.outertune.ui.screens.settings.fragments.SampleButton.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two style pictures of Look and feel are drawn from the buttons pinned (his yes of 20 Nov 2026): the still
 * pictures of September showed Connected's sleep timer under the controls, gone from there since 19 Nov, and no cast
 * button by the title, where it has been since that day.
 */
class PlayerStyleSampleTest {

    private val defaults = PlayerPins.read("")

    @Test
    fun `Classic shows the cast button and the heart by the title, then the menu`() {
        val shown = sampleButtons(PlayerButtonsStyle.CLASSIC, defaults, queueButton = false, casting = true)
        assertEquals(listOf(Pin(PlayerPin.CAST), Pin(PlayerPin.LIKE), Menu), shown.byTitle)
        assertTrue(shown.underControls.isEmpty())
    }

    @Test
    fun `Connected shows cast, share and like by the title, and lyrics and the menu under the controls, no timer`() {
        val shown = sampleButtons(PlayerButtonsStyle.CONNECTED, defaults, queueButton = false, casting = true)
        assertEquals(listOf(Pin(PlayerPin.CAST), Pin(PlayerPin.SHARE), Pin(PlayerPin.LIKE)), shown.byTitle)
        assertEquals(listOf(Pin(PlayerPin.LYRICS), Menu), shown.underControls)
    }

    @Test
    fun `no cast button where the app does not cast`() {
        val classic = sampleButtons(PlayerButtonsStyle.CLASSIC, defaults, queueButton = false, casting = false)
        assertEquals(listOf(Pin(PlayerPin.LIKE), Menu), classic.byTitle)
        val connected = sampleButtons(PlayerButtonsStyle.CONNECTED, defaults, queueButton = false, casting = false)
        assertEquals(listOf(Pin(PlayerPin.SHARE), Pin(PlayerPin.LIKE)), connected.byTitle)
    }

    @Test
    fun `the buttons as pinned, the queue button first, as the player draws them`() {
        val pins = PlayerPins.read("CLASSIC=SHARE+TIMER+LIKE;CONNECTED_TITLE=LIKE;CONNECTED_ROW=TIMER")
        // Beside Classic's queue circle there is room for one fewer: the far one goes, as on the player.
        val classic = sampleButtons(PlayerButtonsStyle.CLASSIC, pins, queueButton = true, casting = true)
        assertEquals(listOf(Queue, Pin(PlayerPin.TIMER), Pin(PlayerPin.LIKE), Menu), classic.byTitle)
        val connected = sampleButtons(PlayerButtonsStyle.CONNECTED, pins, queueButton = true, casting = true)
        assertEquals(listOf(Pin(PlayerPin.LIKE)), connected.byTitle)
        assertEquals(listOf(Queue, Pin(PlayerPin.TIMER), Menu), connected.underControls)
    }

    @Test
    fun `the page draws the pictures, and the still ones are gone`() {
        val page = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/PlayerButtonsFrag.kt").readText()
        assertFalse("R.drawable.player_buttons_" in page)
        assertTrue("PlayerStyleSample(" in page && "pins = pins," in page && "queueButton = queueAsButton," in page)
        // The pins are read before the pictures that draw them.
        assertTrue(page.indexOf("val pins = remember(pinsRaw)") < page.indexOf("PlayerStyleSample("))
        assertFalse(File("src/main/res/drawable-nodpi/player_buttons_classic.webp").exists())
        assertFalse(File("src/main/res/drawable-nodpi/player_buttons_connected.webp").exists())
    }
}
