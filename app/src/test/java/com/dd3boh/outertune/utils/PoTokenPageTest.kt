/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The page that makes po tokens makes its minter once.
 *
 * It asked BotGuard for a new one with every token, and every token then came out longer than the
 * one before (212, 300, 392, 480 characters in one process, 9 Oct 2026). No WebView runs on the
 * JVM, so this reads the page, as SolverPageIsShutTest reads the solver's.
 */
class PoTokenPageTest {
    private val page = File("src/main/assets/po_token.html").readText()

    /** The script without its comments, which say what it used to do. */
    private val code = page.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/**") }.joinToString("\n")

    @Test
    fun `the minter is asked for in one place, and only while the page has none`() {
        assertEquals(1, Regex("""getMinter\(integrityToken\)""").findAll(code).count())
        val guard = code.indexOf("if (!poTokenMinter)")
        val asked = code.indexOf("getMinter(integrityToken)")
        val kept = code.indexOf("poTokenMinter = made")
        val used = code.indexOf("poTokenMinter(identifier)")
        assertTrue("guarded, asked for, kept, then used: $guard $asked $kept $used", guard in 0 until asked && asked < kept && kept < used)
    }

    @Test
    fun `every token comes from the minter the page keeps`() {
        assertEquals(1, Regex("""const result = poTokenMinter\(identifier\)""").findAll(code).count())
        assertEquals("nothing mints from a minter of its own", 0, Regex("""mintCallback\(""").findAll(code).count())
        assertTrue("a new page starts without one", Regex("""var poTokenMinter = null;""").containsMatchIn(code))
    }
}
