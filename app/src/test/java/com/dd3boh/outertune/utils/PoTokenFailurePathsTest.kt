/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the po token code does when making a token goes wrong.
 *
 * The token comes out of a WebView, and every signed-in listener's play asks for one since the
 * address a play is reported to is asked for as the account. When all goes well this code is
 * upstream's, and a phone shows it working. When it does not, upstream's left things behind that
 * cost every later play its whole time limit, or the app itself, and none of that can be run
 * here: there is no WebView in a unit test. So this reads the source, as
 * NoIdentifiersInTheLogTest does, and holds each of those repairs in place, for the day the two
 * files are brought up to date from somewhere else.
 */
class PoTokenFailurePathsTest {

    private val webView = File("src/main/java/com/dd3boh/outertune/utils/potoken/PoTokenWebView.kt").readText()
    private val generator = File("src/main/java/com/dd3boh/outertune/utils/potoken/PoTokenGenerator.kt").readText()

    /** [text] without its comments, so that a word in a sentence is not taken for code. */
    private fun code(text: String): String = text.lines()
        .filterNot { line -> line.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } }
        .joinToString("\n")

    /** The function called [name] in [text], from its first line to the next member's. */
    private fun function(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("$name was not found", start >= 0)
        val next = Regex("""\n    (?:/\*\*|@|(?:private |suspend |override )*fun |val |//region|//endregion)""").find(text, start + 1)
        return code(text.substring(start, next?.range?.first ?: text.length))
    }

    @Test
    fun `an error closes the WebView on its own thread and answers the caller once`() {
        // BotGuard's own errors arrive on the page's JavaScript thread. Closing the WebView there
        // throws, and the caller was never told.
        val onError = function(webView, "onInitializationErrorCloseAndCancel")
        assertTrue("the WebView is closed on whatever thread the error came in on", "mainThread.post" in onError)
        assertTrue(onError.indexOf("mainThread.post") < onError.indexOf("close()"))

        // A continuation answered twice throws, on the main thread. Every answer goes through the one flag.
        val answers = code(webView).lines().filter { "continuation.resume" in it }
        assertEquals("the initialization is answered in two places, ready and failed", 2, answers.size)
        answers.forEach { assertTrue("answered without asking whether it has been: $it", "initializationAnswered.compareAndSet(false, true)" in it) }
    }

    @Test
    fun `a WebView given up on is closed, and a closed one counts as past its time`() {
        val create = function(webView, "getNewPoTokenGenerator")
        assertTrue("one that ran out of time before it was ready is left running", "invokeOnCancellation" in create && "close()" in create)

        val close = function(webView, "close")
        assertTrue("closed = true" in close)
        assertTrue("a second close goes on to a destroyed WebView", "if (closed) return" in close)
        assertTrue("a closed WebView is taken for a working one", Regex("""get\(\) = closed \|\|""").containsMatchIn(code(webView)))
    }

    @Test
    fun `the death of the renderer process is handled, so Android does not end the app`() {
        val gone = function(webView, "onRenderProcessGone")
        assertTrue("return true" in gone)
        assertFalse("return false" in gone)
        assertTrue("onInitializationErrorCloseAndCancel(" in gone)
        assertTrue("webView.webViewClient = " in code(webView))
    }

    @Test
    fun `a WebView is forgotten before its replacement is made, and the replacement kept only once it works`() {
        val body = code(generator)
        val forgotten = body.indexOf("webPoTokenGenerator = null")
        val made = body.indexOf("PoTokenWebView.getNewPoTokenGenerator(")
        val firstToken = body.indexOf("fresh.generatePoToken(")
        val kept = body.indexOf("webPoTokenGenerator = fresh")
        assertTrue("the pieces were not found", listOf(forgotten, made, firstToken, kept).all { it >= 0 })
        assertTrue("a closed WebView is still held while its replacement is made", forgotten < made)
        assertTrue("the replacement is kept before it has made a token", made < firstToken && firstToken < kept)
        // One that made no first token is closed, since nothing holds it.
        assertTrue("fresh.close()" in body)
    }

    @Test
    fun `a page that did not answer in time is not asked again`() {
        val body = code(generator)
        assertTrue("webPoTokenTimedOut = true" in body)
        assertTrue(Regex("""val shouldRecreate =\s*\n?\s*forceRecreate \|\| webPoTokenTimedOut \|\|""").containsMatchIn(body))
        // And running out of time is not a failure to try again after: there is no time left.
        assertTrue("if (throwable is CancellationException) throw throwable" in body)
    }
}
