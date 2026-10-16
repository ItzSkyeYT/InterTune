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
    fun `no text on the card sets its size alone`() {
        // A Text with only fontSize keeps the theme's 24 sp line height: the warning's two lines
        // stood twice their height apart (16 Oct 2026). A text style carries both.
        val code = scanner.lines().filterNot { it.trim().startsWith("//") }
        assertTrue("a text sets fontSize alone: ${code.filter { "fontSize =" in it }}", code.none { "fontSize =" in it })
    }

    @Test
    fun `the options sit on the card's lines, the box on the icons and the label on the titles`() {
        val row = frag.substring(frag.indexOf("private fun ScannerCheckboxRow("))
        // 8 dp, then a 48 dp touch target: the box's centre at 32 dp, on the icons' column
        // (PreferenceEntry: 16 dp, 4 dp, a 24 dp icon). Then 4 dp: the label at 60 dp, on the titles.
        assertTrue(".padding(start = 8.dp" in row)
        assertTrue("Modifier.size(48.dp)" in row)
        assertTrue("Spacer(Modifier.width(4.dp))" in row)
        assertTrue("both options are ScannerCheckboxRow", scanner.split("ScannerCheckboxRow(").size - 1 == 2)
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
