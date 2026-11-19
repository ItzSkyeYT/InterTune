/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import com.dd3boh.outertune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The repeat button's three looks, his pick 3B of 15 Nov 2026 (#182): off is the plain repeat icon
 * at full strength, all is that icon on a lit box, one is the repeat-one icon on the same box. It
 * replaces the grey of 5 Nov, which read as a button that does not work, behind REPEAT_LIT_BOX. The
 * button keeps its place and size (memory: player-controls-stay-put). The source is read, as the
 * flag tests do.
 */
class RepeatLitBoxTest {
    private fun code(name: String) = File("src/main/java/com/dd3boh/outertune/ui/player/$name").readText()

    /** The repeat button's call in [name], from `RepeatButton(` to its click. */
    private fun call(name: String): String = code(name).substringAfter("RepeatButton(").substringBefore("onClick = {")

    @Test
    fun `off is the plain repeat icon, all and one stand on the box`() {
        val row = Color(0xFFE5E2E1)
        assertFalse(RepeatLook.lit(Player.REPEAT_MODE_OFF, litBox = true))
        assertTrue(RepeatLook.lit(Player.REPEAT_MODE_ALL, litBox = true))
        assertTrue(RepeatLook.lit(Player.REPEAT_MODE_ONE, litBox = true))
        assertEquals("off at full strength", row, RepeatLook.iconColor(Player.REPEAT_MODE_OFF, row, litBox = true))
        assertEquals(R.drawable.repeat_on, RepeatLook.icon(Player.REPEAT_MODE_OFF))
        assertEquals(R.drawable.repeat_on, RepeatLook.icon(Player.REPEAT_MODE_ALL))
        assertEquals(R.drawable.repeat_one, RepeatLook.icon(Player.REPEAT_MODE_ONE))
    }

    @Test
    fun `held back, a release keeps the grey of 5 Nov and no box`() {
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        assertTrue("val REPEAT_LIT_BOX = BuildConfig.PREVIEW_FEATURES" in flags)
        val row = Color(0xFFE5E2E1)
        for (mode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)) {
            assertFalse(RepeatLook.lit(mode, litBox = false))
        }
        assertEquals(row.copy(alpha = 0.4f), RepeatLook.iconColor(Player.REPEAT_MODE_OFF, row, litBox = false))
        assertEquals(row, RepeatLook.iconColor(Player.REPEAT_MODE_ALL, row, litBox = false))
        // The flag is read in one place, by the look the two buttons share.
        assertTrue("litBox: Boolean = Unreleased.REPEAT_LIT_BOX" in code("RepeatButton.kt"))
    }

    @Test
    fun `the player and the queue both draw it, off at full strength`() {
        for (name in listOf("Player.kt", "Queue.kt")) {
            val text = code(name)
            assertEquals(name, 1, Regex("""\bRepeatButton\(""").findAll(text).count())
            assertFalse(name, "R.drawable.repeat_" in text)
            assertFalse(name, "REPEAT_OFF_ALPHA" in text)
            // The icon gets its row's own colour, no dimmed copy of it.
            assertFalse(name, ".copy(alpha" in call(name))
        }
        assertFalse("the big 1 stays gone", File("src/main/res/drawable/repeat_off.xml").exists())
    }

    @Test
    fun `the button keeps its place and size`() {
        // The sizes the button had before the box, and the shuffle button's on the other end.
        assertTrue(Regex("""\.size\(transportIconSize\)\s*\.padding\(4\.dp\)\s*\.align\(Alignment\.Center\)""").containsMatchIn(call("Player.kt")))
        assertTrue(Regex("""\.size\(32\.dp\)\s*\.padding\(4\.dp\)""").containsMatchIn(call("Queue.kt")))
        // The box is drawn behind, past the button's bounds, and measures nothing.
        val button = code("RepeatButton.kt")
        val box = button.substringAfter("private fun Modifier.repeatBox(")
        assertTrue("drawBehind {" in box)
        for (layout in listOf(".size(", ".padding(", ".offset(", ".layout(", ".requiredSize(")) assertFalse(layout, layout in box)
        val chain = button.substringAfter("internal fun RepeatButton(").substringBefore("\n}\n")
        for (layout in listOf(".size(", ".padding(", ".offset(", ".requiredSize(")) assertFalse(layout, layout in chain)
        // First in the chain: Modifier.alpha clips to the button's bounds while it is dimmed.
        assertTrue(chain.indexOf(".repeatBox(") < chain.indexOf(".alpha("))
    }

    @Test
    fun `the box is the mockup's, 38dp around a 32dp button with 12dp corners`() {
        assertEquals(38f, 32f + RepeatLook.BoxOutset.value * 2, 0.001f)
        assertEquals(12f, 38f * RepeatLook.BOX_CORNER, 0.001f)
    }
}
