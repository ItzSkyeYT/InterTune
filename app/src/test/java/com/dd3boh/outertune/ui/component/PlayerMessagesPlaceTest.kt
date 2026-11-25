/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His screenshot of 24 Nov 2026: the queue's "Removed Running / Undo" sat on the full player's Play and Pause for its ten
 * seconds. The app's messages were placed for the closed player, over the mini player's place; with the full player open
 * they come at the top now, under the status bar.
 */
class PlayerMessagesPlaceTest {
    @Test
    fun `with the full player open, messages come at the top, else above the mini player as before`() {
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val host = main.substringAfter("SnackbarHost(\n").substringBefore("\n                            )\n")
        val open = host.substringAfter("if (playerBottomSheetState.isExpanded) {").substringBefore("} else {")
        val closed = host.substringAfter("} else {")
        assertTrue(host, "hostState = snackbarHostState," in host)
        assertTrue(open, ".windowInsetsPadding(WindowInsets.statusBars)" in open && ".align(Alignment.TopCenter)" in open)
        assertTrue(closed, ".windowInsetsPadding(LocalPlayerAwareWindowInsets.current)" in closed && ".align(Alignment.BottomCenter)" in closed)
    }
}
