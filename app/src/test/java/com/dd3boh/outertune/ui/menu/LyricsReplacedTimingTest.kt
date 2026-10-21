/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lyrics that replace the ones a listener moved start as they come, whichever way they came: a
 * move made for the old lines does not fit new ones. Only a pick in Search said so. Refetch could
 * bring other lyrics that were already in time, and they played as late as the old ones had been
 * moved. Edit keeps the move: it is the same lyrics, mended. This reads the source, as
 * LyricsOpeningTest does.
 */
class LyricsReplacedTimingTest {
    private val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/LyricsMenu.kt").readText()

    @Test
    fun `refetched lyrics that differ from the ones there forget the move`() {
        val refetch = menu.substringAfter("title = R.string.refetch", missingDelimiterValue = "").substringBefore("GridMenuItem(")
        assertTrue("Refetch could not be found", refetch.isNotEmpty())
        assertTrue(refetch, "if (fresh != null && fresh != before) onOtherLyrics(song.id)" in refetch)
    }

    @Test
    fun `deleted lyrics take their move with them`() {
        val delete = menu.substringAfter("showDeleteLyric = false\n                        onDismiss()", missingDelimiterValue = "")
            .substringBefore("Text(text = stringResource(android.R.string.ok))")
        assertTrue("the delete could not be found", delete.isNotEmpty())
        assertTrue(delete, "onOtherLyrics(" in delete)
    }
}
