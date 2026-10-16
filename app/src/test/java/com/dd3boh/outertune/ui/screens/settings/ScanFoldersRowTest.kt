/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The folders to scan lead Local media's scanner card, as a setting with an icon and what is chosen
 * under it. Below the Scan button, with no icon and no line under it, the row read as a heading,
 * and it is what most people come to that page for (his word, 16 Oct 2026).
 */
class ScanFoldersRowTest {
    private val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LocalMediaSettingsFrag.kt").readText()
    private val scanner: String = run {
        val start = frag.indexOf("fun ColumnScope.LocalScannerFrag()")
        assertTrue("the scanner card was not found", start >= 0)
        frag.substring(start, frag.indexOf("fun ColumnScope.LocalScannerExtraFrag()", start).takeIf { it > 0 } ?: frag.length)
    }

    @Test
    fun `the folders come before the Scan button`() {
        val row = scanner.indexOf("Modifier.tourTarget(Tour.SETTING_SCAN_PATHS)")
        val scan = scanner.indexOf("R.string.scanner_btn_idle")
        assertTrue("the folders row is gone", row >= 0)
        assertTrue("the Scan button is gone", scan >= 0)
        assertTrue("the folders row is below the Scan button again", row < scan)
    }

    @Test
    fun `the row has an icon and says what is chosen`() {
        val row = scanner.substring(scanner.indexOf("PreferenceEntry(", scanner.indexOf("val chosenFolders")), scanner.indexOf("R.string.scanner_btn_idle"))
        assertTrue("the row has no icon", "Icons.Rounded.FolderOpen" in row)
        assertTrue("the row no longer says when nothing is chosen", "R.string.scan_paths_none" in row)
        assertTrue("the row no longer names the chosen folders", "chosenFolders.joinToString" in row)
        assertTrue("the row no longer opens the folder list", "showAddFolderDialog = true" in row)
    }
}
