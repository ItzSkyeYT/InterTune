/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The time the sleep timer shows (rememberSleepTimerStatus: the player's button, the menu's entry and the sheet) is
 * looked at once a second only while the app can be seen. A LaunchedEffect is not stopped when the screen goes off
 * or another app comes in front, so its loop went on waking the main thread every second for the timer's whole run,
 * and at End of queue or End of album each look walks the rest of the queue (review of 0.13, performance.md 4). No
 * Compose runs on the JVM, so this reads the source, as the other wiring tests do.
 */
class SleepTimerStatusWhileSeenTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** The body of the function [name] in [text], from its first brace to the one that closes it. */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("no fun $name", start >= 0)
        val open = text.indexOf('{', text.indexOf(')', start))
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        throw AssertionError("$name never closes")
    }

    private val status = body(code(File(sources, "ui/component/SleepTimer.kt").readText()), "rememberSleepTimerStatus")

    @Test
    fun `the time left is looked at only while the app is seen, behind a dialog too`() {
        assertTrue("LocalLifecycleOwner.current" in status)
        val effect = Regex("""LaunchedEffect\(([^)]*)\)""").find(status)
        assertTrue("no LaunchedEffect", effect != null)
        // Keyed on the owner too: a new owner is a new loop under it.
        assertTrue(effect!!.groupValues[1], "lifecycleOwner" in effect.groupValues[1].split(",").map { it.trim() })
        val seen = status.indexOf("lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)")
        assertTrue("the loop is not under repeatOnLifecycle(STARTED)", seen >= 0)
        // One loop, and it is inside the block: nothing looks at the timer while the app is not seen.
        assertEquals(1, Regex("""while \(""").findAll(status).count())
        assertTrue(status.indexOf("while (") > seen)
        assertTrue(status.indexOf("timer.status()") > seen)
        assertTrue(status.indexOf("delay(1000L)") > seen)
    }

    @Test
    fun `a timer that stops still clears what is shown at once`() {
        // Before the lifecycle: a timer that ends while the app is in the background shows nothing when it comes back.
        val cleared = status.indexOf("status = null")
        assertTrue(cleared >= 0)
        assertTrue(cleared < status.indexOf("repeatOnLifecycle("))
        assertTrue("return if (active) status ?: timer.status() else null" in status)
    }
}
