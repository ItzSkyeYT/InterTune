/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His pick of 15 Nov 2026, "C with B's badges": the cast button on the player and nothing under the
 * controls, the pill that names where music plays kept as a switch that is off ("the pill should be
 * toggleable"), and a badge on the mini player's cover while a room or a cast is on, all behind
 * CAST_AT_TOP. The button stood alone at the top right until 20 Nov 2026, when it became one of the
 * player's buttons by the title (his word: "it looks bad on both portrait and landscape"; CastPinTest);
 * a tablet, which shows no buttons by the title, keeps it at the top right. Partly read from the
 * source, as the flag tests are: what Compose draws where cannot be looked at on the JVM.
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
        assertTrue("R.string.output_pill, words = listOf(R.string.cast)" in File(folder, "ui/screens/settings/SettingsIndex.kt").readText())
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
    fun `only a tablet keeps a cast button at the top right, everywhere else it is one of the player's buttons`() {
        val calls = Regex("""\bCastButton\(""").findAll(player).map { it.range.first }.toList()
        assertEquals(1, calls.size)
        val at = calls.single()
        assertEquals("if (tabletTwoPane) {", player.substring(player.lastIndexOf("if (", at), at).lines().first().trim())
        val call = player.substring(at, player.indexOf("\n                            )", at))
        assertTrue(call, ".align(Alignment.TopEnd)" in call && ".padding(end = 4.dp)" in call)
        // The pinned one is not drawn on a tablet's two panes, so it never shows twice.
        assertTrue("val castShown = castLook != null && !tabletTwoPane" in player)
    }

    @Test
    fun `the pill stays in a window's corner on its side only with the switch on`() {
        // A narrow window on its side (split screen, a 16:9 phone with its navigation bar beside the screen) kept the
        // pill whatever the switch said, its only way to the cast sheet. The cast button is one of its buttons now,
        // above the title there, so nothing is special about it any more.
        assertTrue("narrowSide" !in player)
        val corner = player.lastIndexOf("OutputButton(")
        val condition = player.substring(player.lastIndexOf("if (", corner), corner).lines().first().trim()
        assertEquals(
            "if (outputPill && isLandscape && queueLayout.outputRoom == 0.dp && !tabletTwoPane && mediaMetadata != null && !queueCovers) {",
            condition,
        )
    }

    @Test
    fun `turning the pill off moves nothing, its room under the controls is kept`() {
        // The queue's arrow and the controls stay where the pill left them, so flipping the switch moves nothing.
        assertTrue("outputRow = Unreleased.CASTING," in player)
        assertTrue("bottom = if (tabletTwoPane) TabletQueueHandleReserve" in player)
        assertTrue("else queueReserve - queueLayout.outputRoom" in player)
        // And the half itself is kept with the switch off: only the pill in it goes. Without it the cover, the title, the
        // seek bar and the controls all dropped by the half (48dp) under everyone who updated from 0.12, where the pill
        // always stood (seen on the emulator, 25 Nov 2026: pause at y 2206 with the pill, 2350 without, on 1344x2992).
        assertTrue("if (queueLayout.outputRoom > 0.dp) {" in player)
        assertFalse("if (queueLayout.outputRoom > 0.dp && outputPill)" in player)
        val half = player.substring(player.indexOf("if (queueLayout.outputRoom > 0.dp) {"))
        assertTrue(".height(queueLayout.outputRoom)" in half.substring(0, half.indexOf("OutputButton(")))
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
