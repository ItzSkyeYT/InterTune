/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Controls whose state TalkBack did not say, seen in passing by the review of 0.13 (accessibility.md, "Old, not new in
 * 0.13"): each now says what it is set to, in words the app already has or in Android's own. Nothing is drawn
 * differently. No Compose runs on the JVM, so this reads the source, as the other wiring tests do.
 */
class TalkBackStatesTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the sleep timer's slider says its name and the length it is at, not a percent`() {
        val sheet = source("ui/component/SleepTimer.kt")
        val slider = sheet.substringAfter("        Slider(\n").substringBefore("\n        )\n")
        assertTrue(slider, "valueRange = 1f..120f" in slider)
        assertTrue(slider, "contentDescription = sliderName" in slider)
        // The length as the line above the slider says it ("30 minutes").
        assertTrue(slider, "stateDescription = pluralString" in slider)
        assertTrue("val sliderName = stringResource(R.string.sleep_timer)" in sheet)
        assertTrue("val pluralString = pluralStringResource(R.plurals.minute, minutes, minutes)" in sheet)
    }

    @Test
    fun `the player's lyrics buttons say whether lyrics are on, as a switch does`() {
        val player = source("ui/player/Player.kt")
        // Every lyrics button of the player, the circle by the title and the segments of both rows, passes its state.
        val lyrics = Regex("""contentDescription = stringResource\(R\.string\.toggle_lyrics\),""").findAll(player).toList()
        assertEquals(3, lyrics.size)
        for (button in lyrics) {
            // The rest of that call: up to its closing bracket, the first line that holds nothing else.
            val rest = player.substring(button.range.first)
            val call = rest.substring(0, Regex("""\n\s*\)\n""").find(rest)!!.range.first)
            assertTrue(call, "onClick = toggleLyrics" in call)
            assertTrue(call, "on = showLyrics," in call)
        }
        // Both kinds of button carry it on the node that is tapped, after its click.
        for (header in listOf("private fun PlayerCircleButton(", "private fun PlayerActionSegment(")) {
            val body = player.substringAfter(header).substringBefore("\n}\n")
            assertTrue(header, "on: Boolean? = null," in body)
            assertTrue(header, Regex("""onClick\(\)\n\s+}\n\s+\.onOffState\(on\)""").containsMatchIn(body))
        }
        // A switch, in Android's own words ("On", "Off"): no new text. Nothing for a button that only acts.
        val state = player.substringAfter("private fun Modifier.onOffState(on: Boolean?): Modifier =").substringBefore("\n\n")
        assertTrue(state, "if (on == null) this else semantics {" in state)
        assertTrue(state, "role = Role.Switch" in state)
        assertTrue(state, "toggleableState = ToggleableState(on)" in state)
    }

    @Test
    fun `the player menu's sleep timer entry says its name before the time left`() {
        val entry = source("ui/menu/GridMenu.kt").substringAfter("fun LazyGridScope.SleepTimerGridMenu(").substringBefore("\n}\n")
        val shown = "if (enabled && sleepTimerTimeLeft > 0)"
        // The words are the time left exactly when the moon names the entry, so it is said once either way.
        assertTrue(entry, "text = $shown makeTimeString(sleepTimerTimeLeft) else stringResource(" in entry)
        assertTrue(entry, "contentDescription = $shown stringResource(R.string.sleep_timer) else null," in entry)
    }
}
