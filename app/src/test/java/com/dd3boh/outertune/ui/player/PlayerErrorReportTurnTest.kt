/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Report a problem opened from the player's error screen stays open when the phone turns (review of 0.13,
 * 18 Nov 2026). The screen kept the page's state itself, and the player puts the cover, and the error screen
 * in it, at other places upright and on its side (two covers each, with swipe to skip or without): a turn
 * started the screen again there, and the page closed. The typed words came back from ReportDraft; the open page did not. The player
 * keeps it now, above both layouts. Read from the source, as the other tests of these screens do.
 */
class PlayerErrorReportTurnTest {
    private val src = File("src/main/java/com/dd3boh/outertune/ui/player")
    private val player = File(src, "Player.kt").readText()

    @Test
    fun `the page is the player's, above the layouts of either way the phone is held`() {
        val state = player.indexOf("var errorReport by remember { mutableStateOf<PlayerErrorReport?>(null) }")
        val layouts = player.indexOf("val lol: @Composable BoxScope.() -> Unit = {")
        assertTrue("the state is the player's", state >= 0)
        assertTrue("above the layouts", state in 0 until layouts)
        assertEquals("one page, there", 1, Regex("""ProblemReportDialog\(""").findAll(player).count())
        val dialog = player.indexOf("errorReport?.let { report -> ProblemReportDialog(onDismiss = { errorReport = null }, playerError = report) }")
        assertTrue("shown from the player, above the layouts", dialog in 0 until layouts)
    }

    @Test
    fun `every cover of the player hands its Report up to the player`() {
        val covers = Regex("""\bThumbnail\(""").findAll(player).count()
        assertEquals("two upright, two on its side", 4, covers)
        assertEquals(covers, Regex("""onReport = reportError,""").findAll(player).count())
        val thumbnail = File(src, "Thumbnail.kt").readText()
        assertTrue("onReport = onReport," in thumbnail.substringAfter("ThumbnailPlaybackError(").substringBefore(")\n"))
    }

    @Test
    fun `the error screen hands the report up when it has where to, and opens the page itself only when not`() {
        val screen = File(src, "ThumbnailPlaybackError.kt").readText()
        assertTrue("onReport: ((PlayerErrorReport) -> Unit)? = null," in screen)
        assertTrue("TextButton(onClick = { if (onReport != null) onReport(playerErrorReport(error, song)) else reporting = true })" in screen)
    }
}
