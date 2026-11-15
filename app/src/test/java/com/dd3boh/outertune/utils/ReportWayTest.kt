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
            val guarded = source.substringAfter("if (!withGitHub) {").substringBefore("singleLine = true")
            assertTrue(path, "R.string.problem_report_contact" in guarded)
            assertEquals(path, 1, Regex("R\\.string\\.problem_report_contact\\)").findAll(source).count())
        }
    }

    /**
     * 7 Nov 2026: a send the site turned away (the app's build, which it did not take) painted the
     * contact box red with "Couldn't send it", as if the address were the fault. The failure has a
     * line of its own, and the box stays as it was.
     */
    @Test
    fun `a failed send says so on its own line, not on the contact box`() {
        for (path in dialogs) {
            val source = read(path)
            assertFalse(path, "isError = failed" in source)
            assertTrue(path, "if (outcome is ReportRelay.Outcome.Failed) {" in source)
            assertTrue(path, "supportingText = { Text(stringResource(R.string.problem_report_contact_note)) }" in source)
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
        // The words as kept until sent (ReportDraft): the same box's words.
        assertTrue("appendQueryParameter(if (tester != null) TESTER_WHAT_KEY else BUG_WHAT_KEY, words.what.trim().take(FORM_WHAT_LIMIT))" in problem)
        // The keys are the forms' box ids: the app's constants and the two files must agree, or the box
        // stays empty without a sound (it did: the testers' form said what, the app sent what-happens).
        for ((form, constant) in listOf("bug_report.yml" to "BUG_WHAT_KEY", "tester_report.yml" to "TESTER_WHAT_KEY")) {
            val key = Regex("""private const val $constant = "([^"]+)"""").find(problem)!!.groupValues[1]
            val yml = File("../.github/ISSUE_TEMPLATE/$form").readText()
            assertTrue("$form has no box with id $key", Regex("""(?m)^\s*id: ${Regex.escape(key)}\s*$""").containsMatchIn(yml))
        }
        assertTrue("what" == Regex("""TESTER_WHAT_KEY = "([^"]+)"""").find(problem)!!.groupValues[1])
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
