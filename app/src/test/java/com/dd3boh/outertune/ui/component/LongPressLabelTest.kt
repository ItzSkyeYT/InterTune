/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A long press on the selection pill's buttons and on the queue's shuffle and repeat says what they do
 * (Niko in #testers, 11 Nov 2026), by the same name screen readers hear, so the two never disagree.
 * Read from the source, as the other tests of these screens do.
 */
class LongPressLabelTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")
    private val label = File(sources, "ui/component/LongPressLabel.kt").readText()
    private val pill = File(sources, "ui/component/SelectHeader.kt").readText()
    private val queue = File(sources, "ui/player/Queue.kt").readText()

    @Test
    fun `the label is Material's plain tooltip, shown above the button on a long press`() {
        assertTrue(label.contains("TooltipBox("))
        assertTrue(label.contains("tooltip = { PlainTooltip { Text(label) } }"))
        assertTrue(label.contains("TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above)"))
    }

    @Test
    fun `every button of the pill says its own name, the one screen readers hear`() {
        for (name in listOf("R.string.options", "if (allSelected) R.string.select_all else R.string.deselect_all", "R.string.cancel_selection", "R.string.remove_from_queue")) {
            assertTrue(name, pill.contains("LongPressLabel(stringResource($name)) {"))
            assertTrue("$name is also its content description", pill.contains("contentDescription = stringResource($name)"))
        }
        // No button of the pill is left without one.
        assertEquals(Regex("""IconButton\(""").findAll(pill).count(), Regex("""LongPressLabel\(""").findAll(pill).count())
    }

    @Test
    fun `the queue's shuffle and repeat say their name and state, and stay centred in their place`() {
        for (name in listOf("shuffleName(shuffleModeEnabled)", "repeatName(repeatMode)")) {
            assertTrue(name, queue.contains("LongPressLabel($name, Modifier.align(Alignment.Center)) {"))
            assertTrue("$name is also its accessible name", queue.contains(".named($name)"))
        }
        // The centring moved to the label's box: on the button inside it, it would centre nothing.
        for (name in listOf("shuffleName(shuffleModeEnabled)", "repeatName(repeatMode)")) {
            val inside = queue.substringAfter("LongPressLabel($name, Modifier.align(Alignment.Center)) {").substringBefore(".named($name)")
            assertFalse(name, inside.contains(".align("))
        }
    }
}
