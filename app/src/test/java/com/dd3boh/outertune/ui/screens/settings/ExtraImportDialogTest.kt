/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 122. The dialog behind Storage and downloads > Advanced > Configure extra import locations
 * was titled "Scan paths", a copy of the scan folders' dialog. Music/Artistes added there looked
 * like a scan folder, and the scan folders' dialog then refused Music for holding a "download
 * folder": the import folder, which the refusal did not tell apart from the real one.
 */
class ExtraImportDialogTest {
    private val downloads = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DownloadsFrag.kt").readText()
    private val scan = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LocalMediaSettingsFrag.kt").readText()

    private val importDialog: String = run {
        val start = downloads.indexOf("if (showPathsDialog) {")
        assertTrue("the extra import dialog was not found", start >= 0)
        downloads.substring(start, downloads.indexOf("if (showImportDialog) {", start))
    }

    @Test
    fun `the extra import dialog is not called Scan paths`() {
        val code = importDialog.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertFalse("the dialog wears the scan folders' title again", "R.string.scan_paths_incl" in code)
        assertTrue("the dialog has no title of its own", "R.string.dl_extra_path_dialog_title" in code)
    }

    @Test
    fun `the extra import dialog says what the folders are for`() {
        assertTrue("the dialog no longer explains itself", "R.string.dl_extra_path_description" in importDialog)
    }

    @Test
    fun `the scan folders dialog names the setting that is in the way`() {
        assertTrue("an extra import folder is no longer told apart", "R.string.scanner_holds_import_dir" in scan)
        assertTrue("a download folder is no longer told apart", "R.string.scanner_holds_download_dir" in scan)
    }

    @Test
    fun `the line under a refused scan folder is the rule, not the row's words read the other way round`() {
        assertTrue("the rule is gone", "R.string.scanner_rule" in scan)
        // "cannot be a subdirectory of the download folder" is the one row about a folder inside it;
        // under a row saying a folder holds the download folder it read the wrong way round
        assertTrue(
            "the subdirectory rule is used again beyond its own row",
            scan.split("R.string.scanner_rejected_dir").size - 1 == 1
        )
    }
}
