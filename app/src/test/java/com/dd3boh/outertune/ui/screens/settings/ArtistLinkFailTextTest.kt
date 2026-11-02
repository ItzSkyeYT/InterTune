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
 * When the job that links local artists to YouTube's stopped, the full scan in the settings said
 * so with the success text, "YouTube artist linking job complete: <why it stopped>", and the scan
 * at launch said "Scanner failed" for a scan that had finished (audit of 2 Nov 2026, 3.10). Both
 * say the linking failed now, as the quick scan always did.
 */
class ArtistLinkFailTextTest {
    private val src = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `every stop of the artist linking says it failed`() {
        for (file in listOf("ui/screens/settings/fragments/LocalMediaSettingsFrag.kt", "MainActivityUtils.kt")) {
            val source = File(src, file).readText()
            var from = source.indexOf("localToRemoteArtist(database)")
            assertTrue("$file links artists", from > 0)
            while (from > 0) {
                val caught = source.substring(source.indexOf("catch (e: ScannerAbortException)", from)).substringBefore("}\n")
                assertTrue("$file: $caught", caught.contains("R.string.scanner_ytm_link_fail"))
                from = source.indexOf("localToRemoteArtist(database)", from + 1)
            }
        }
    }

    @Test
    fun `the success text is never followed by a reason`() {
        for (file in listOf("ui/screens/settings/fragments/LocalMediaSettingsFrag.kt", "MainActivityUtils.kt")) {
            assertFalse(file, File(src, file).readText().contains("scanner_ytm_link_success)}: \${e.message}"))
        }
    }
}
