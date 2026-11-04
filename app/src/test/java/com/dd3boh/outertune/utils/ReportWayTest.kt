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
 * Anonymous or with a GitHub account, his rule of 28 Oct 2026: anonymous for people who would rather not
 * sign in, the account way kept once picked, anonymous still one tap away. Read from the sources, like
 * ForkGuardTest, since the dialogs are Compose.
 */
class ReportWayTest {
    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
    private val dialogs = listOf("ui/component/ProblemReportDialog.kt", "ui/component/CrashReportDialog.kt")

    @Test
    fun `anonymous comes first, and both pages keep the same pick`() {
        assertEquals(ReportWay.ANONYMOUS, ReportWay.entries.first())
        for (path in dialogs) {
            val source = read(path)
            assertTrue(path, "var way by rememberEnumPreference(ReportWayKey, ReportWay.ANONYMOUS)" in source)
            assertTrue(path, "val withGitHub = relay && way == ReportWay.GITHUB" in source)
            assertTrue(path, "ReportWayChoice(way, onPick = { way = it })" in source)
        }
        assertTrue(read("constants/PreferenceKeys.kt").contains("val ReportWayKey = stringPreferencesKey(\"reportWay\")"))
    }

    @Test
    fun `the way to reach someone is asked only for an anonymous report`() {
        for (path in dialogs) {
            val source = read(path)
            val guarded = source.substringAfter("if (!withGitHub) {").substringBefore("isError = failed")
            assertTrue(path, "R.string.problem_report_contact" in guarded)
            assertEquals(path, 1, Regex("R\\.string\\.problem_report_contact\\)").findAll(source).count())
        }
    }

    @Test
    fun `the picked way is the big button, and the other is one tap away above`() {
        val problem = read("ui/component/ProblemReportDialog.kt")
        assertTrue("if (withGitHub) Button(enabled = text != null, onClick = openForm)" in problem)
        val crash = read("ui/component/CrashReportDialog.kt")
        assertTrue("if (withGitHub) Button(onClick = openGitHub)" in crash)
        // Without the relay (a released build, or a fork) nothing changes: the form or nothing.
        for (path in dialogs) assertTrue(path, "else if (BuiltInKeys.official) TextButton(" in read(path))
    }

    @Test
    fun `what was typed goes into the form's own box`() {
        val problem = read("ui/component/ProblemReportDialog.kt")
        assertTrue("appendQueryParameter(\"what-happens\", what.trim().take(FORM_WHAT_LIMIT))" in problem)
        // The id is the form's: renaming the box there would empty it silently.
        assertTrue(File("../.github/ISSUE_TEMPLATE/bug_report.yml").readText().contains("id: what-happens"))
    }

    @Test
    fun `the four words exist in English and French`() {
        val names = listOf("report_way_anonymous", "report_way_github", "report_way_anonymous_note", "report_way_github_note")
        for (folder in listOf("values", "values-fr")) {
            val texts = File("src/main/res/$folder/strings-ot.xml").readText()
            for (name in names) assertTrue("$folder $name", "name=\"$name\"" in texts)
        }
    }
}
