/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a poll's or an announcement's hand-written text becomes on screen. Plain text must come
 * through untouched, since every document written before formatting existed is plain text.
 */
class BodyBlocksTest {

    @Test
    fun `plain text is one paragraph, its line breaks kept`() {
        val blocks = bodyBlocks("Line one\nLine two")
        assertEquals(listOf(BodyBlock.Paragraph("Line one\nLine two", emptyList(), false)), blocks)
    }

    @Test
    fun `a blank line starts a new paragraph with room before it`() {
        val blocks = bodyBlocks("First\n\n\nSecond\r\n\r\nThird")
        assertEquals(listOf("First", "Second", "Third"), blocks.map { it.text })
        assertEquals(listOf(false, true, true), blocks.map { it.gap })
    }

    @Test
    fun `leading and trailing blank lines add nothing`() {
        val blocks = bodyBlocks("\n\nOnly\n\n")
        assertEquals(listOf(BodyBlock.Paragraph("Only", emptyList(), false)), blocks)
    }

    @Test
    fun `list items, with any of three markers`() {
        val blocks = bodyBlocks("Why join:\n- news\n* early builds\n  • help")
        assertEquals(
            listOf(
                BodyBlock.Paragraph("Why join:", emptyList(), false),
                BodyBlock.Item("news", emptyList(), false),
                BodyBlock.Item("early builds", emptyList(), false),
                BodyBlock.Item("help", emptyList(), false),
            ),
            blocks,
        )
    }

    @Test
    fun `a marker needs a space after it`() {
        val blocks = bodyBlocks("-5 degrees\n*nothing*")
        assertEquals(listOf(BodyBlock.Paragraph("-5 degrees\n*nothing*", emptyList(), false)), blocks)
    }

    @Test
    fun `headings`() {
        val blocks = bodyBlocks("# What is new\nText\n\n## Also")
        assertEquals(
            listOf(
                BodyBlock.Heading("What is new", emptyList(), false),
                BodyBlock.Paragraph("Text", emptyList(), false),
                BodyBlock.Heading("Also", emptyList(), true),
            ),
            blocks,
        )
    }

    @Test
    fun `a hash without a space is text`() {
        assertEquals("#1 in the charts", bodyBlocks("#1 in the charts").single().text)
    }

    @Test
    fun `bold loses its asterisks and keeps its place`() {
        val block = bodyBlocks("Join **today** and **say hi**").single()
        assertEquals("Join today and say hi", block.text)
        assertEquals(listOf(5..9, 15..20), block.bold)
        assertEquals("today", block.text.substring(block.bold[0]))
        assertEquals("say hi", block.text.substring(block.bold[1]))
    }

    @Test
    fun `bold at the start of a line is not a list item`() {
        val block = bodyBlocks("**New:** a Discord server").single()
        assertTrue(block is BodyBlock.Paragraph)
        assertEquals("New: a Discord server", block.text)
        assertEquals(listOf(0..3), block.bold)
    }

    @Test
    fun `bold inside a list item`() {
        val block = bodyBlocks("- **Fast** answers").single()
        assertTrue(block is BodyBlock.Item)
        assertEquals("Fast answers", block.text)
        assertEquals(listOf(0..3), block.bold)
    }

    @Test
    fun `an unpaired double asterisk stays as typed`() {
        val block = bodyBlocks("5 ** 2 is 25").single()
        assertEquals("5 ** 2 is 25", block.text)
        assertTrue(block.bold.isEmpty())
    }

    @Test
    fun `links are found in the text without the marks`() {
        val block = bodyBlocks("- **Join:** discord.gg/abc").single()
        val (range, url) = webLinks(block.text).single()
        assertEquals("discord.gg/abc", block.text.substring(range))
        assertEquals("https://discord.gg/abc", url)
    }
}
