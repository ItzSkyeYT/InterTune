/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * File sizes keep their numbers (1024 to a step, a step up past 900) and take their units from the
 * app's language, so a French screen writes Mo, not MB.
 */
class FileSizeTest {
    private val french = listOf("o", "Ko", "Mo", "Go", "To", "Po")

    @Test
    fun `the numbers are as before`() {
        assertEquals("900 B", formatFileSize(900))
        assertEquals("0 KB", formatFileSize(901))
        assertEquals("14 MB", formatFileSize(14L * 1024 * 1024))
        assertEquals("512 MB", formatFileSize(512L * 1024 * 1024))
        assertEquals("2 GB", formatFileSize(2L * 1024 * 1024 * 1024))
        assertEquals("-3 KB", formatFileSize(-3L * 1024))
    }

    @Test
    fun `French writes its own units`() {
        assertEquals("14 Mo", formatFileSize(14L * 1024 * 1024, french))
        assertEquals("512 o", formatFileSize(512, french))
        assertEquals("2 Go", formatFileSize(2L * 1024 * 1024 * 1024, french))
    }

    @Test
    fun `the French units are there and the settings screens pass their context`() {
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        val units = fr.substringAfter("<string-array name=\"file_size_units\">").substringBefore("</string-array>")
        assertEquals(french, Regex("<item>([^<]+)</item>").findAll(units).map { it.groupValues[1] }.toList())
        for (path in listOf(
            "src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/CacheFrag.kt",
            "src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DownloadsFrag.kt",
            "src/main/java/com/dd3boh/outertune/ui/screens/SetupWizard.kt",
        )) {
            val code = File(path).readText()
            assertTrue("$path writes sizes", "formatFileSize(context, " in code)
            assertFalse("$path writes a size without its language", Regex("""(?<![\w.])formatFileSize\((?!context, )""").containsMatchIn(code))
        }
    }
}
