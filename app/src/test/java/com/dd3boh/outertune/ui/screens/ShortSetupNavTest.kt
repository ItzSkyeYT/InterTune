/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Under every page of the short setup: back, how far along, and on, the row the wizard has under
 * its own pages. Asked for on 9 Oct 2026, the pages having had a button of their own each and
 * nothing to say how many of them there were.
 */
class ShortSetupNavTest {

    private val setup = File("src/main/java/com/dd3boh/outertune/ui/screens/ShortSetup.kt").readText()

    /** The text without its comments, so a sentence that names a thing is not taken for the thing. */
    private val code = setup
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the line is empty on the first page and full on the last, as the wizard's is`() {
        assertEquals(0f, setupProgress(0), 0f)
        assertEquals(0.5f, setupProgress(1), 0f)
        assertEquals(1f, setupProgress(2), 0f)
    }

    @Test
    fun `a page number from a longer wizard is the last page's`() {
        // Setup opened again by an update, with the number of a page these three do not have.
        assertEquals(1f, setupProgress(5), 0f)
        assertEquals(0f, setupProgress(-1), 0f)
    }

    @Test
    fun `the row is drawn once, for the screen, and not by a page`() {
        // A page that had it of its own could be without it; one outside the pages cannot.
        assertEquals(1, Regex("""\bSetupNavRow\(""").findAll(code.substringBefore("private fun SetupNavRow(")).count())
        val pages = code.substringAfter("AnimatedContent(").substringBefore("SetupNavRow(")
        assertTrue("the row comes after the pages", "PAGE_WELCOME -> SetupPage(" in pages)
    }

    @Test
    fun `no page keeps a way on of its own beside the row`() {
        // Two buttons that do the same thing, one of them moving from page to page.
        for (gone in listOf("setup_get_started", "setup_not_now", "setup_more")) {
            assertFalse(gone, gone in code)
            assertFalse("$gone is still a string", gone in File("src/main/res/values/strings-ot.xml").readText())
        }
        // What a page does that is not going on stays with the page.
        assertTrue("oobe_use_backup" in code)
        assertTrue("R.string.setup_sign_in)" in code)
    }

    @Test
    fun `the row says what its buttons are`() {
        for (name in listOf("R.string.action_back", "R.string.action_next", "R.string.action_done")) {
            assertTrue(name, name in code.substringAfter("private fun SetupNavRow("))
        }
    }
}
