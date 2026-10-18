/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * InterTune takes shared text (a YouTube Music link someone sends it), so a plain share sheet of
 * its own offered InterTune as a place to share lyrics or a song to (18 Oct 2026). Every share goes
 * through shareSheet, which leaves the app out.
 */
class ShareSheetTest {

    @Test
    fun `every share sheet of the app leaves InterTune out`() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val choosers = sources.filter { it.readText().contains("Intent.createChooser(") }.map { it.name }.toList()
        assertEquals("only the helper opens a share sheet", listOf("ShareSheet.kt"), choosers)
        val helper = File("src/main/java/com/dd3boh/outertune/utils/ShareSheet.kt").readText()
        assertTrue(helper.contains("Intent.EXTRA_EXCLUDE_COMPONENTS"))
        assertTrue(helper.contains("ComponentName(this, MainActivity::class.java)"))
    }
}
