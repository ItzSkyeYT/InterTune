/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His pick of 15 Nov 2026, "C with B's badges": the cast button at the player's top right and nothing
 * under the controls, the pill that names where music plays kept as a switch that is off ("the pill
 * should be toggleable"), and a badge on the mini player's cover while a room or a cast is on, all
 * behind CAST_AT_TOP. Partly read from the source, as the flag tests are: what Compose draws where
 * cannot be looked at on the JVM.
 */
class CastAtTopTest {
    private val folder = File("src/main/java/com/dd3boh/outertune")
    private val player = File(folder, "ui/player/Player.kt").readText()

    @Test
    fun `the pill is a switch, off unless it is turned on`() {
        assertTrue(Regex("""val OutputPillKey = booleanPreferencesKey\("outputPill"\)""").containsMatchIn(File(folder, "constants/PreferenceKeys.kt").readText()))
        assertTrue("rememberPreference(OutputPillKey, defaultValue = false)" in player)
        val settings = File(folder, "ui/screens/settings/fragments/PlayerButtonsFrag.kt").readText()
        assertTrue("rememberPreference(OutputPillKey, defaultValue = false)" in settings)
        assertTrue("Modifier.settingRow(R.string.output_pill)" in settings)
        assertTrue("R.string.output_pill, R.string.output_pill_description" in File(folder, "ui/screens/settings/SettingsIndex.kt").readText())
    }

    @Test
    fun `on for everyone in 0_13, and with the flag off the pill stays under the controls with no switch and no badge`() {
        assertTrue("val CAST_AT_TOP = true" in File(folder, "constants/Unreleased.kt").readText())
        // Without the pick the pill is always shown, whatever the switch says.
        assertTrue("val outputPill = !Unreleased.CAST_AT_TOP || outputPillSwitch" in player)
        assertTrue("if (Unreleased.CASTING && Unreleased.CAST_AT_TOP) {" in File(folder, "ui/screens/settings/fragments/PlayerButtonsFrag.kt").readText())
        assertTrue("there = Unreleased.CAST_AT_TOP)" in File(folder, "ui/screens/settings/SettingsIndex.kt").readText())
        assertTrue("if (Unreleased.CAST_AT_TOP) MiniPlayerBadge()" in File(folder, "ui/player/MiniPlayer.kt").readText())
    }

    @Test
    fun `the pill is drawn with the switch on, upright and on its side`() {
        val pills = Regex("""\bOutputButton\(""").findAll(player).count()
        assertEquals(2, pills)
        var from = 0
        repeat(pills) {
            val at = player.indexOf("OutputButton(", from)
            val ahead = player.substring(player.lastIndexOf("if (", at), at)
            assertTrue(ahead, "outputPill" in ahead && "!outputPill" !in ahead)
            from = at + 1
        }
    }

    @Test
    fun `the cast button stands at the top right while the pill is off`() {
        val calls = Regex("""\bCastButton\(""").findAll(player).map { it.range.first }.toList()
        // What each is drawn under: upright, in narrow landscape and on a tablet, the cover's top right
        // under the status bar; on its side, the top right of the cover's pane.
        val conditions = calls.map { at -> player.substring(player.lastIndexOf("if (", at), at).lines().first().trim() }.toSet()
        assertEquals(setOf("if (tabletTwoPane || (!outputPill && !compactPlayer && !narrowSide)) {", "if (!outputPill) {"), conditions)
        for (at in calls) {
            val call = player.substring(at, player.indexOf("\n                            )", at))
            assertTrue(call, ".align(Alignment.TopEnd)" in call && ".padding(end = 4.dp)" in call)
        }
    }

    @Test
    fun `a narrow window on its side keeps the pill in its corner and gets no cast button at the top`() {
        // Under 600dp wide on its side (split screen, a 16:9 phone with its navigation bar beside the screen, a
        // floating window) the stacked layout's cover gets what the controls leave, often no height at all: a cast
        // button at its top right had none either, and with the pill gone there was no way to the cast sheet. So
        // that window stays as a release has it, the switch or not.
        assertTrue("val narrowSide = isLandscape && !tabMode && !wideScreen" in player)
        val corner = player.lastIndexOf("OutputButton(")
        val condition = player.substring(player.lastIndexOf("if (", corner), corner).lines().first().trim()
        assertEquals(
            "if ((outputPill || narrowSide) && isLandscape && queueLayout.outputRoom == 0.dp && !tabletTwoPane && mediaMetadata != null && !queueCovers) {",
            condition,
        )
        assertTrue("if (tabletTwoPane || (!outputPill && !compactPlayer && !narrowSide)) {" in player)
    }

    @Test
    fun `turning the pill off moves nothing, its room under the controls is kept`() {
        // The queue's arrow and the controls stay where the pill left them, so flipping the switch moves nothing.
        assertTrue("outputRow = Unreleased.CASTING," in player)
        assertTrue("bottom = if (tabletTwoPane) TabletQueueHandleReserve" in player)
        assertTrue("else queueReserve - queueLayout.outputRoom" in player)
    }

    @Test
    fun `the mini player's badge says a room first, then a cast, and nothing on this phone alone`() {
        assertEquals(MiniBadge.ROOM, miniBadge(inRoom = true, casting = false))
        assertEquals(MiniBadge.CAST, miniBadge(inRoom = false, casting = true))
        assertEquals(MiniBadge.ROOM, miniBadge(inRoom = true, casting = true))
        assertNull(miniBadge(inRoom = false, casting = false))
        // On the cover itself, after what the cover shows while it waits or fails.
        val mini = File(folder, "ui/player/MiniPlayer.kt").readText()
        val cover = mini.substringAfter("fun MiniMediaInfo(").substringBefore("        Column(")
        assertTrue("MiniPlayerBadge()" in cover)
        assertTrue(cover.indexOf("MiniPlayerBadge()") > cover.indexOf("Icons.Rounded.Info"))
        // The mockup's: a 20dp circle with a 2dp ring and a 14dp icon.
        assertEquals(20.dp, MiniBadgeSize)
        assertEquals(2.dp, MiniBadgeRing)
        assertEquals(14.dp, MiniBadgeIcon)
    }
}
