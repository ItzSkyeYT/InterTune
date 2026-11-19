/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A folder's page said "0 songs" over its own list of songs on every visit after the first (the walks on the 0.13
 * integration, 19 Nov 2026, after Exclude folder and back; the same since the folder page of May 2025): the first visit
 * loads the folder and keeps its tree, a later one finds the tree kept and loads nothing, and the count, which is no part
 * of the tree, stayed at its start of 0. A page that opens on a kept tree now reads the count too.
 */
class FolderCountEveryVisitTest {
    @Test
    fun `a folder opened from the kept tree reads its count`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/library/FolderScreen.kt").readText()
        val firstLook = screen.substringAfter("LaunchedEffect(Unit) {").substringBefore("val mutableSongs")
        val kept = firstLook.substringAfter("if (!currDir.isSkeleton) {").substringBefore("if (!viewModel.uiInit) {")
        assertTrue(kept, kept.contains("viewModel.uiInit = true"))
        assertTrue(kept, kept.contains("coroutineScope.launch(Dispatchers.IO) { viewModel.getSongCount() }"))
        // A first visit still loads the folder and its count together.
        val first = firstLook.substringAfter("if (!viewModel.uiInit) {")
        assertTrue(first, first.contains("viewModel.getLocalSongs()") && first.contains("viewModel.getSongCount()"))
    }
}
