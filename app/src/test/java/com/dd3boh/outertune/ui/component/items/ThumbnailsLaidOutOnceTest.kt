/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A thumbnail is measured for its size only where the size is wanted: a cover on the phone that no
 * size was asked for. Every other one is a plain Box, where each was a BoxWithConstraints, a
 * second composition for every row of every list. Read from the source, comments taken out.
 */
class ThumbnailsLaidOutOnceTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `a list's thumbnail is measured only for a cover on the phone with no size asked`() {
        val thumbnail = source("ui/component/items/Items.kt").substringAfter("fun ItemThumbnail(", missingDelimiterValue = "")
            .substringBefore("\n}\n")
        assertTrue("ItemThumbnail could not be found", thumbnail.isNotEmpty())
        assertEquals(1, Regex("""\bBoxWithConstraints\(""").findAll(thumbnail).count())
        val measured = thumbnail.indexOf("BoxWithConstraints(")
        assertTrue(thumbnail.substring(0, measured).contains("if (thumbnailUrl?.startsWith(\"/storage\") == true && preferredSize <= 0) {"))
        assertTrue(thumbnail.contains("content(preferredSize)"))
    }

    @Test
    fun `the mini player's cover is a plain box`() {
        val info = source("ui/player/MiniPlayer.kt").substringAfter("fun MiniMediaInfo(", missingDelimiterValue = "")
        assertTrue("MiniMediaInfo could not be found", info.isNotEmpty())
        assertTrue(!info.contains("BoxWithConstraints"))
    }
}
