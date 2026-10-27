/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Two small things of 27 Oct 2026: the widget's settings no longer close the app, and Share looks centred. */
class WidgetTaskAndShareNudgeTest {
    @Test
    fun `the widget's settings open in a task of their own`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".widget.WidgetConfigActivity\"").substringBefore(">")
        assertTrue(entry.contains("android:taskAffinity=\"\""))
    }

    @Test
    fun `every Share in the player is nudged to look centred`() {
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        val shares = Regex("""painter = rememberVectorPainter\(Icons\.Rounded\.Share\),\s*nudge = SHARE_NUDGE,""").findAll(player).count()
        assertEquals(Regex("""rememberVectorPainter\(Icons\.Rounded\.Share\)""").findAll(player).count(), shares)
        assertTrue(player.contains("private val SHARE_NUDGE = (-1).dp"))
    }
}

/** dvd, 27 Oct 2026: a downloaded song never played online had no cover offline. */
class DownloadKeepsCoverTest {
    @Test
    fun `a finished download fetches the covers lists and the player ask for`() {
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        assertTrue(util.contains("finishedSinceMove += download.request.id\n                        keepCoverForOffline(download.request.id)"))
        val keep = util.substringAfter("private fun keepCoverForOffline(").substringBefore("\n    }\n")
        assertTrue(keep.contains("listOf(url, url.resize(ART_PREVIEW_PX, ART_PREVIEW_PX))"))
        assertTrue(keep.contains("context.imageLoader.enqueue("))
    }
}
