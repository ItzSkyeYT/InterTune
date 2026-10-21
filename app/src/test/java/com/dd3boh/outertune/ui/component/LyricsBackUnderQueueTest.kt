/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lyrics' own Back, the timing bar's and the picking's, steps aside while the queue sheet over
 * them takes Back. Their handlers are put in place after the queue's, which is there from the
 * moment the player opens, so the newest won: one Back with the queue up closed the timing bar
 * hidden under it, and the queue stayed. This reads the source, as ScreenBackHandlerTest does.
 */
class LyricsBackUnderQueueTest {

    private val lyrics = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    @Test
    fun `every Back of the lyrics gives way to the queue`() {
        val handlers = Regex("""(?<![A-Za-z])BackHandler\(([^)]*)\)""").findAll(lyrics).map { it.groupValues[1] }.toList()
        assertEquals("the handlers this was written for", 2, handlers.size)
        handlers.forEach { assertTrue(it, "enabled = !queueTakesBack" in it) }
        assertTrue("LocalQueueSheet.current?.takesBack == true" in lyrics)
    }

    @Test
    fun `the player tells the lyrics where its queue is`() {
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        assertTrue(Regex("""CompositionLocalProvider\(LocalQueueSheet provides queueSheetState\)\s*\{\s*lol\(\)""").containsMatchIn(player))
    }
}
