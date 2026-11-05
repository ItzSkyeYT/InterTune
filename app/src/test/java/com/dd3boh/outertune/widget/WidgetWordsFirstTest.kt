/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The widget shows the new song and the right button at once, and gets its pictures when they are
 * there. The title and the play state used to be written only after three covers had been
 * downloaded one after the other, under the lock every later write waits for. This reads the
 * source, because the order of a write and a download cannot be seen without a phone.
 */
class WidgetWordsFirstTest {

    private val store = File("src/main/java/com/dd3boh/outertune/widget/WidgetStore.kt").readText()

    private val setNowPlaying = store
        .substringAfter("suspend fun setNowPlaying(")
        .substringBefore("private suspend fun picturesFor(")

    private val hydrate = store
        .substringAfter("suspend fun hydrate(")
        .substringBefore("private suspend fun artFor(")

    private val setList = store
        .substringAfter("suspend fun setList(")
        .substringBefore("private suspend fun rowPicturesFor(")

    /** A write of the words, its arguments on one line or several. */
    private val writes = Regex("""\bwrite\(\s*context\b""")

    /** What runs under the lock: up to the first brace that closes at the lock's own depth. */
    private val locked = setNowPlaying.substringAfter("mutex.withLock {").substringBefore("\n        }\n")

    @Test
    fun `the words are written under the lock, and nothing is downloaded there`() {
        assertTrue(locked, writes.containsMatchIn(locked))
        for (download in listOf("artFor(", "artInSizes(", "picturesFor(", "imageLoader")) {
            assertFalse("$download under the lock holds up the title and the play button", download in locked)
        }
    }

    @Test
    fun `the widget is told to draw before the pictures are fetched`() {
        val draws = setNowPlaying.indexOf("updateAll(context)")
        val fetches = setNowPlaying.indexOf("picturesFor(")
        assertTrue(draws in 0 until fetches)
        assertTrue("the fetch is after the lock is let go", fetches > setNowPlaying.indexOf(locked) + locked.length)
    }

    @Test
    fun `a row of Home is written without waiting for its pictures either`() {
        // the same lock: a download under it here holds up the next song's title just the same
        val rows = setList.substringAfter("mutex.withLock {").substringBefore("\n        }\n")
        assertTrue(rows, writes.containsMatchIn(rows))
        for (download in listOf("artFor(", "artInSizes(", "rowPicturesFor(", "imageLoader")) {
            assertFalse("$download under the lock holds up the title and the play button", download in rows)
        }
        assertTrue(setList.indexOf("updateAll(context)") in 0 until setList.indexOf("rowPicturesFor("))
    }

    @Test
    fun `a widget that has just appeared is drawn before its pictures are fetched`() {
        val filling = hydrate.substringAfter("mutex.withLock {").substringBefore("\n        }\n")
        assertTrue(filling, writes.containsMatchIn(filling))
        for (download in listOf("artFor(", "artInSizes(", "imageLoader")) {
            assertFalse("$download under the lock holds up the title and the play button", download in filling)
        }
        assertTrue(hydrate.indexOf("updateAll(context)") in 0 until hydrate.indexOf("artInSizes("))
        // the rows it reads from the library are read under that lock
        val fromLibrary = hydrate.substringAfter("private suspend fun fromLibrary(")
        assertFalse("artFor(" in fromLibrary)
        assertFalse("imageLoader" in fromLibrary)
    }

    /**
     * The widget of the report of 4 Nov 2026 (experiments/bugs/widget-1104) was a card with nothing
     * on it after it was added. A widget just added is written and drawn from what the snapshot
     * holds before the library is read: History alone is every play there has been. And the library
     * is read with no lock held, since every play and pause waits for that lock.
     */
    @Test
    fun `a widget that has just appeared is drawn before the library is read, which is read outside the lock`() {
        val draws = hydrate.indexOf("updateAll(context)")
        val reads = hydrate.indexOf("fromLibrary(context")
        assertTrue("$draws, $reads", draws in 0 until reads)
        val body = hydrate.substringBefore("private suspend fun shownLists(")
        var at = body.indexOf("mutex.withLock {")
        var locks = 0
        while (at >= 0) {
            val block = body.substring(at, closing(body, body.indexOf('{', at)) + 1)
            assertFalse(block, "fromLibrary(" in block)
            locks++
            at = body.indexOf("mutex.withLock {", at + 1)
        }
        assertTrue(locks >= 2)
    }

    /** Where the block opened by the brace at [open] closes, counting braces. */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        error("no closing brace after $open")
    }

    @Test
    fun `a new widget draws the cover of what is playing before it fetches a single row's`() {
        // On a phone that never had a widget, every list's covers are missing: a few dozen downloads,
        // and the one picture anybody was waiting for used to come after the last of them.
        val afterCover = hydrate.substringAfter("artInSizes(")
        val draws = afterCover.indexOf("updateAll(context)")
        assertTrue(draws in 0 until afterCover.indexOf("artFor("))
        // and the rows are drawn as each list's covers come in, not once at the very end
        val rows = afterCover.substringAfter("for (batch in batches)").substringBefore("private suspend fun shownLists(")
        assertTrue(rows, "updateAll(context)" in rows)
    }

    @Test
    fun `the lists the widgets show get their covers first, and every list gets them`() {
        val order = rowOrder(listOf(WidgetList.RECENT, WidgetList.KEEP_LISTENING))
        assertTrue(order.take(2) == listOf(WidgetList.RECENT, WidgetList.KEEP_LISTENING))
        assertTrue(order.size == WidgetList.entries.size && order.toSet() == WidgetList.entries.toSet())
        assertTrue(rowOrder(emptyList()) == WidgetList.entries)
    }
}
