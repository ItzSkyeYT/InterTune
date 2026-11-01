/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His two notes on the Testers page (1 Nov 2026): the rules "just doesn't open at all" (the row had
 * no tap), and "when they report an issue there should be a dedicated issue form for testers".
 */
class TesterReportTest {
    private fun ids(form: String) = Regex("""(?m)^\s+id: (\w+)""").findAll(File("../.github/ISSUE_TEMPLATE/$form").readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `a tester's report opens the testers' own form, which has every box the app fills`() {
        assertTrue(ids("tester_report.yml").containsAll(listOf("build", "tester", "device", "logs")))
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/component/ProblemReportDialog.kt").readText()
        assertTrue(dialog.contains("TESTER_FORM = \"tester_report.yml\""))
        assertTrue(dialog.contains("if (tester != null) TESTER_FORM else BUG_FORM"))
        assertTrue("the number goes with it, never the code", dialog.contains("appendQueryParameter(\"tester\", tester.toString())"))
    }

    @Test
    fun `everyone else's report still opens the bug form, which has the boxes it fills`() {
        assertTrue(ids("bug_report.yml").containsAll(listOf("build", "device", "logs")))
        assertTrue(File("src/main/java/com/dd3boh/outertune/ui/component/ProblemReportDialog.kt").readText().contains("BUG_FORM = \"bug_report.yml\""))
    }

    @Test
    fun `the rules row opens the rules in full, in every language the page has`() {
        val page = File("src/preview/java/com/dd3boh/outertune/testerlock/TestersPage.kt").readText()
        assertTrue(page.contains("onClick = { rules = true }"))
        assertTrue(page.contains("R.string.testers_rules_full"))
        for (values in listOf("values", "values-fr")) {
            assertTrue(values, File("src/preview/res-lock/$values/strings-tester.xml").readText().contains("name=\"testers_rules_full\""))
        }
    }
}
