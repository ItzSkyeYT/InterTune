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
import java.util.Calendar
import java.util.TimeZone

/**
 * A report shared as a file (his yes of 2 Nov 2026: "InterTune-report.txt with Date+Time"):
 * Discord takes a file from anyone, where a long message wants Nitro.
 */
class ReportFileTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the file is named with the day and the minute`() {
        val zone = TimeZone.getDefault()
        val at = Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 2, 18, 30, 45)
        }.timeInMillis
        assertEquals("InterTune-report-2026-11-02-1830.txt", ReportFile.name(at))
    }

    @Test
    fun `the name has nothing a file system or a chat app would refuse`() {
        val name = ReportFile.name(System.currentTimeMillis())
        assertTrue(name, Regex("InterTune-report-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.txt").matches(name))
    }

    @Test
    fun `it is shared from the cache through the app's own provider`() {
        val file = source("utils/ReportFile.kt")
        assertTrue(file.contains("File(context.cacheDir, FOLDER)"))
        assertTrue(file.contains("FileProvider.getUriForFile(context, \"\${context.packageName}.FileProvider\", file)"))
        assertTrue(file.contains("addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
        // The provider covers the whole cache folder, so the reports folder is in it.
        val paths = File("src/main/res/xml/provider_paths.xml").readText()
        assertTrue(paths.contains("<cache-path") && paths.contains("path=\".\""))
    }

    @Test
    fun `both report pages offer it under the report, behind its flag`() {
        for (page in listOf("ui/component/ProblemReportDialog.kt", "ui/component/CrashReportDialog.kt")) {
            assertTrue(page, source(page).contains("if (Unreleased.REPORT_FILE) ShareReportFile(text)"))
        }
        // On in every build from 0.12.8 (his yes of 4 Nov 2026).
        assertTrue(source("constants/Unreleased.kt").contains("val REPORT_FILE = true"))
    }

    @Test
    fun `its words are held in English and written in French`() {
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue(en.contains("<string name=\"problem_report_share_file\" translatable=\"false\">Share as a file</string>"))
        assertTrue(fr.contains("<string name=\"problem_report_share_file\">Partager en fichier</string>"))
    }
}
