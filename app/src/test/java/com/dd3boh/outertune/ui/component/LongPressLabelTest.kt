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
        for (name in listOf("R.string.options", "if (allSelected) R.string.select_all else R.string.deselect_all", "R.string.cancel_selection")) {
            assertTrue(name, pill.contains("LongPressLabel(stringResource($name)) {"))
            assertTrue("$name is also its content description", pill.contains("contentDescription = stringResource($name)"))
        }
        // No button of the pill is left without one.
        assertEquals(Regex("""IconButton\(""").findAll(pill).count(), Regex("""LongPressLabel\(""").findAll(pill).count())
    }

    @Test
    fun `the queue's shuffle and repeat say their name and state`() {
        for (name in listOf("shuffleName(shuffleModeEnabled)", "repeatName(repeatMode)")) {
            assertTrue(name, queue.contains("LongPressLabel($name, Modifier.align(Alignment.Center)) {"))
            assertTrue("$name is also its accessible name", queue.contains(".named($name)"))
        }
    }

    /**
     * The text between the bracket that opens at [open] in [text] and the one that closes it: a call's
     * arguments, or a block's body.
     */
    private fun inside(text: String, open: Int): String {
        val close = when (text[open]) { '(' -> ')'; '{' -> '}'; else -> error("not a bracket at $open") }
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == text[open]) depth++
            if (text[i] == close && --depth == 0) return text.substring(open + 1, i)
        }
        error("no closing bracket for $open")
    }

    @Test
    fun `the label's modifier places the box its parent places, never Material's inner box`() {
        // material3 1.4.0's TooltipBox puts its modifier on the box holding the button, inside an outer box
        // of its own at the top left (BasicTooltipBox and WrappedAnchor in the cached jar, 18 Nov 2026). An
        // align handed to it centred the button in a box its own size: the queue's buttons sat at the left
        // of their slots from 13 Nov. So the modifier goes on a box around the TooltipBox, and nothing of it
        // goes into the TooltipBox.
        val start = label.indexOf("fun LongPressLabel(")
        val bodyAt = label.indexOf('{', label.indexOf(")", label.indexOf("content: @Composable () -> Unit", start)))
        val body = inside(label, bodyAt).trim()
        assertTrue("the first thing is the placed box: $body", body.startsWith("Box(modifier) {"))
        val boxBody = inside(body, body.indexOf('{'))
        val tooltipArgs = inside(boxBody, boxBody.indexOf("TooltipBox(") + "TooltipBox".length)
        assertFalse("TooltipBox gets no modifier of the caller's: $tooltipArgs", tooltipArgs.contains("modifier"))
    }

    @Test
    fun `the label shows on a long press, not at each button a keypad or a keyboard moves to`() {
        // material3 1.4.0 shows a TooltipBox's label when the button in it takes key focus: its keyboardBehavior
        // reads the focus it sees with isFocused. Behind a focus group that focus is the group's, which only has
        // a child focused, so a keypad phone moving along the queue's bar pops no label at each button.
        val start = label.indexOf("fun LongPressLabel(")
        val bodyAt = label.indexOf('{', label.indexOf(")", label.indexOf("content: @Composable () -> Unit", start)))
        val body = inside(label, bodyAt)
        val tooltip = body.substring(body.indexOf("TooltipBox("))
        val anchor = inside(tooltip, tooltip.indexOf('{', tooltip.indexOf(')', tooltip.indexOf("state = rememberTooltipState()"))))
        assertTrue("the button sits in a focus group inside the tooltip's anchor: $anchor", anchor.trim().endsWith("Box(Modifier.focusGroup()) { content() }"))
        assertFalse("Material's own input stays on, for the long press", label.contains("enableUserInput = false"))
        // What the group answers: the library asks isFocused, which a group with a focused child is not.
        val shows = javaClass.classLoader!!.getResourceAsStream("androidx/compose/material3/internal/BasicTooltipKt\$keyboardBehavior\$1\$1.class")
        assertTrue("material3's tooltip has changed: look at how it shows on focus again", shows != null)
        val code = String(shows!!.use { it.readBytes() }, Charsets.ISO_8859_1)
        assertTrue(code.contains("isFocused"))
        assertFalse("hasFocus would see through the group", code.contains("hasFocus"))
    }

    @Test
    fun `the queue's seven buttons sit in the middle of their slots, as before 13 Nov`() {
        // His rule: the player's controls keep their place and size. Before the labels came, each button
        // had align(Center) in a slot of equal width; now the align is the label's, on the box the slot places.
        val bar = queue.substringAfter("// player controls\n            if (queueState != null && !compact)").substringBefore("Spacer(Modifier.height(16.dp))")
        val slots = bar.split("Box(modifier = Modifier.weight(1f)) {").drop(1)
        assertEquals("shuffle, previous, back, play, forward, next, repeat", 7, slots.size)
        for (slot in slots) {
            val labels = Regex("""LongPressLabel\(([^\n]*), Modifier\.align\(Alignment\.Center\)\) \{""").findAll(slot).toList()
            assertEquals("one centred label in the slot: ${slot.take(160)}", 1, labels.size)
            val button = inside(slot, labels[0].range.last)
            assertFalse("no align on the button inside the label, where it would centre nothing: ${button.take(160)}", button.contains(".align("))
        }
    }

    @Test
    fun `the queue's transport buttons and the player's rows of buttons say their name too`() {
        for (name in listOf("stringResource(R.string.widget_previous)", "seekBackName()", "playPauseName(isPlaying && playbackState != STATE_ENDED)", "seekForwardName()", "stringResource(R.string.widget_next)", "saveQueueDescription")) {
            assertTrue(name, queue.contains("LongPressLabel($name"))
        }
        // The player's circles beside the title and its connected segments say theirs from one place each,
        // by the content description they already have, and a button without a name shows nothing.
        val player = File(sources, "ui/player/Player.kt").readText()
        for (fn in listOf("private fun PlayerCircleButton(", "private fun PlayerActionSegment(")) {
            val body = player.substringAfter(fn).substringBefore("\n}\n")
            assertTrue(fn, body.contains("OptionalLongPressLabel(contentDescription"))
        }
        assertTrue(player.contains("if (label.isNullOrBlank()) Box(modifier) { content() } else LongPressLabel(label, modifier, content)"))
    }
}
