/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.scanners

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The scan at launch syncs the way the Settings page's quick scan does, for every scanner (B4, 29 Oct 2026). */
class LaunchScanFollowsScannerTest {
    @Test
    fun `with MediaStore chosen the launch scan uses MediaStore's own sync`() {
        val launch = File("src/main/java/com/dd3boh/outertune/MainActivityUtils.kt").readText()
        val branch = Regex("""if \(scannerImpl == ScannerImpl\.MEDIASTORE\) \{\s*//[^\n]*\n\s*//[^\n]*\n\s*scanner\.fullMediaStoreSync\(""")
        assertTrue("MediaStore goes through fullMediaStoreSync at launch", branch.containsMatchIn(launch))
        assertTrue("the others keep the quick sync", "scanner.quickSync(database, uris, scannerSensitivity, strictExtensions, strictFilePaths)" in launch)
    }
}
