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

    private val setNowPlaying = File("src/main/java/com/dd3boh/outertune/widget/WidgetStore.kt").readText()
        .substringAfter("suspend fun setNowPlaying(")
        .substringBefore("private suspend fun picturesFor(")

    /** What runs under the lock: up to the first brace that closes at the lock's own depth. */
    private val locked = setNowPlaying.substringAfter("mutex.withLock {").substringBefore("\n        }\n")

    @Test
    fun `the words are written under the lock, and nothing is downloaded there`() {
        assertTrue("write(context" in locked)
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
}
