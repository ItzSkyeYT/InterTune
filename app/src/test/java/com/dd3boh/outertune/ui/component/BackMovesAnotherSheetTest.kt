/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A sheet whose Back moves another one, the queue's moving the player when Back from the queue
 * closes the player (issue #30). The player's content, the queue and its handler with it, goes at
 * the player's collapsed height, so a preview that reached it would take the gesture with it: called
 * off, and the player sprung open again. The preview stops just short, and letting go closes it.
 */
class BackMovesAnotherSheetTest {
    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/BottomSheet.kt").readText()

    @Test
    fun `a sheet's own Back still takes it all the way to its collapsed height`() {
        for (bound in listOf(0.dp, 96.dp, 140.dp)) {
            assertEquals(bound, backPreviewEnd(bound, movesAnother = false))
        }
    }

    @Test
    fun `another sheet's Back stops short of that sheet's collapsed height`() {
        for (bound in listOf(0.dp, 96.dp, 140.dp)) {
            assertTrue(backPreviewEnd(bound, movesAnother = true) > bound)
        }
        // Short by little enough not to show.
        assertTrue(BackPreviewShortBy <= 2.dp)
    }

    @Test
    fun `the preview moves the sheet the Back is for, and to where backPreviewEnd says`() {
        assertTrue(source.contains("backMoves: BottomSheetState = state,"))
        assertTrue(source.contains("val to = backPreviewEnd(moves.collapsedBound, movesAnother = moves !== state)"))
        assertTrue(source.contains("moves.seekTo(lerp(from, to, eased))"))
        // Letting go closes it the rest of the way; abandoning the gesture puts it back.
        assertTrue(source.contains("moves.collapseSoft()\n            } catch (cancelled: CancellationException) {"))
        assertTrue(source.contains("moves.expandSoft()"))
    }
}
