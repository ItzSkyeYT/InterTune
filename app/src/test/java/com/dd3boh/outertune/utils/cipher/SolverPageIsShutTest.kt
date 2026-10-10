/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The page the solver runs in stays shut.
 *
 * What runs in that page is YouTube's player script, cut up by somebody else's solver. It is
 * given no network, no files and no address of its own to stand at, and of the app only the
 * calls it needs to take a question and hand back an answer. None of that can be shown by a test
 * that runs, there being no WebView on the JVM, and each of them is one line that somebody
 * chasing a bug could change. So this reads the source, as NoIdentifiersInTheLogTest does.
 */
class SolverPageIsShutTest {
    private val source = File("src/main/java/com/dd3boh/outertune/utils/cipher/WebViewChallengeSolver.kt").readText()

    /** The code without its comments, which speak of the very things it must not do. */
    private val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")

    @Test
    fun `the page has no network and reaches no file`() {
        assertTrue(Regex("""blockNetworkLoads\s*=\s*true""").containsMatchIn(code))
        assertTrue(Regex("""allowFileAccess\s*=\s*false""").containsMatchIn(code))
        assertTrue(Regex("""allowContentAccess\s*=\s*false""").containsMatchIn(code))
        for (opening in listOf("blockNetworkLoads = false", "allowFileAccess = true", "allowContentAccess = true", "allowUniversalAccessFromFileURLs", "allowFileAccessFromFileURLs", "domStorageEnabled = true")) {
            assertFalse(opening, opening in code)
        }
    }

    @Test
    fun `the page stands at no address, and nothing is ever loaded into it from one`() {
        assertTrue("its one load is the page's own text, with no base address", "loadDataWithBaseURL(null, PAGE" in code)
        assertEquals(1, Regex("""loadDataWithBaseURL\(""").findAll(code).count())
        // The only other load is the blank page it is closed with.
        assertEquals(listOf("about:blank"), Regex("""loadUrl\("([^"]*)"\)""").findAll(code).map { it.groupValues[1] }.toList())
        assertFalse("http" in code.replace("https://github.com", ""))
    }

    @Test
    fun `the page can call five things of the app and no more`() {
        val calls = Regex("""@JavascriptInterface\s+fun (\w+)""").findAll(code).map { it.groupValues[1] }.toList()
        assertEquals(listOf("onPageReady", "input", "player", "onSolved", "onFailed"), calls)
        assertEquals("one bridge, under one name", 1, Regex("""addJavascriptInterface\(""").findAll(code).count())
    }
}
