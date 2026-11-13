/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A text field left with the focus under the player, the search box most often, kept its cursor's
 * round handle drawn over the player as it came up: the handle is a little window of its own (Ti-Die,
 * #167, 13 Nov 2026). As soon as the player leaves the bottom, the focus leaves the field, by touch;
 * with the keys, the effect beside it moves the focus to play and pause instead. Read from the source.
 */
class PlayerTakesFocusTest {
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()

    @Test
    fun `as the player comes up by touch, no text field under it keeps the focus`() {
        val effect = player.substringAfter("// A text field left with the focus under the player").substringBefore("\n    }\n")
        assertTrue("the effect is there", effect.length < player.length)
        assertTrue("from the first moment it leaves the bottom", effect.contains("snapshotFlow { state.progress > 0f }"))
        assertTrue("by touch only: the keys have their own place to go", effect.contains("inputModeManager.inputMode != InputMode.Keyboard"))
        assertTrue(effect.contains("focusManager.clearFocus()"))
    }

    @Test
    fun `with the keys, opening the player still moves the focus to play and pause`() {
        assertTrue(player.contains("if (keys && !queueCovers) runCatching { playPauseFocus.requestFocus() }"))
    }
}
