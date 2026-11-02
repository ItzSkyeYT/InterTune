/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The crash page says when a newer version is out (the dead-ends audit, 1.10: 8 of 14 crash reports
 * came from builds that already had a newer release). Only what the update check already found is
 * used: that check is opt in, and the crash page never asks GitHub itself.
 */
class CrashNewerTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `a newer version found by the update check is named`() {
        assertEquals("0.12.8", crashNewerVersion(foundCode = 100, foundName = "0.12.8", running = 99))
    }

    @Test
    fun `nothing is said for this version, an older one, or none found`() {
        assertNull(crashNewerVersion(foundCode = 99, foundName = "0.12.7", running = 99))
        assertNull(crashNewerVersion(foundCode = 98, foundName = "0.12.6", running = 99))
        assertNull(crashNewerVersion(foundCode = -1, foundName = "", running = 99))
        assertNull("a code with no name is not shown half", crashNewerVersion(foundCode = 100, foundName = "", running = 99))
    }

    @Test
    fun `the crash page reads only what the check stored, and asks nobody`() {
        val dialog = source("ui/component/CrashReportDialog.kt")
        assertTrue(dialog.contains("rememberPreference(LastVersionCodeKey, -1)"))
        assertTrue(dialog.contains("rememberPreference(LastVersionKey, \"\")"))
        assertTrue("no check from the crash page", !dialog.contains("UpdateChecker") && !dialog.contains(".check("))
    }

    @Test
    fun `Update opens the Updates settings, or F-Droid for a copy from there`() {
        val dialog = source("ui/component/CrashReportDialog.kt")
        assertTrue(dialog.contains("fdroidPageUrl(context.packageName)"))
        assertTrue(dialog.contains("SETTINGS_INDEX.first { it.title == R.string.check_for_update }.jump"))
        assertTrue(dialog.contains("R.string.update_prompt_fdroid"))
    }

    @Test
    fun `the words are held in English and written in French`() {
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("crash_newer_title", "crash_newer_body", "crash_newer_update")) {
            assertTrue(name, en.contains("<string name=\"$name\" translatable=\"false\">"))
            assertTrue(name, fr.contains("<string name=\"$name\">"))
        }
    }
}
