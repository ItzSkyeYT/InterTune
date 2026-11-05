/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Send to InterTune (his idea, mock and plan of 27 Oct 2026): what the app sends intertune.app and
 * how it reads the answer. The site's side has its own tests (tools/site/report.test.mjs).
 */
class ReportRelayTest {
    private val url = "https://github.com/ItzSkyeYT/InterTune/issues/58"

    private fun sent(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `the report goes as the page showed it, the way to reach them beside it and never inside`() {
        val report = "10-27 19:48:40 E/Search: could not load (HTTP 400)\n"
        val json = sent(ReportRelay.body("0.12.6 (98) core release", "Pixel 5, Android 14", report, "Search: could not load", " pale#1234 "))
        assertEquals(report, json["report"]!!.jsonPrimitive.content)
        assertEquals("pale#1234", json["contact"]!!.jsonPrimitive.content)
        assertEquals("0.12.6 (98) core release", json["build"]!!.jsonPrimitive.content)
        assertFalse(json["report"]!!.jsonPrimitive.content.contains("pale"))
    }

    @Test
    fun `nothing typed, nothing sent for it`() {
        val json = sent(ReportRelay.body("0.12.6 (98)", "Pixel 5", "a log line long enough", null, "   "))
        assertFalse("contact" in json)
        assertFalse("title" in json)
    }

    @Test
    fun `what the person wrote goes with the report, trimmed and cut to what the site takes`() {
        val json = sent(ReportRelay.body("0.12.6 (98)", "Pixel 5", "log", null, null, "  It stopped after a skip.  "))
        assertEquals("It stopped after a skip.", json["what"]!!.jsonPrimitive.content)
        assertEquals(2000, sent(ReportRelay.body("0.12.6 (98)", "Pixel 5", "log", null, null, "w".repeat(3000)))["what"]!!.jsonPrimitive.content.length)
        assertFalse("what" in sent(ReportRelay.body("0.12.6 (98)", "Pixel 5", "log", null, null, "   ")))
        assertFalse("what" in sent(ReportRelay.body("0.12.6 (98)", "Pixel 5", "log", null, null)))
    }

    @Test
    fun `a long title or contact is cut to what the site takes`() {
        val json = sent(ReportRelay.body("0.12.6 (98)", "Pixel 5", "log", "t".repeat(300), "c".repeat(300)))
        assertEquals(120, json["title"]!!.jsonPrimitive.content.length)
        assertEquals(200, json["contact"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `the site's answers`() {
        assertEquals(ReportRelay.Outcome.Sent(58, url), ReportRelay.outcome(201, """{"number":58,"url":"$url"}"""))
        assertEquals(ReportRelay.Outcome.Sent(0, url), ReportRelay.outcome(201, """{"number":0,"url":"$url","dry":true}"""))
        assertEquals(ReportRelay.Outcome.Held, ReportRelay.outcome(202, """{"held":true}"""))
        assertEquals(ReportRelay.Outcome.Failed("too many"), ReportRelay.outcome(429, """{"error":"too many"}"""))
        assertEquals(ReportRelay.Outcome.Failed("github"), ReportRelay.outcome(502, """{"error":"github"}"""))
        assertEquals(ReportRelay.Outcome.Failed("HTTP 500"), ReportRelay.outcome(500, "<html>"))
        assertEquals(ReportRelay.Outcome.Failed("unreadable answer"), ReportRelay.outcome(201, "{}"))
    }

    @Test
    fun `the issue's title is the fault's own words, without its time`() {
        val lines = listOf(
            ProblemLog.Line("19:48:35.108", 'I', "MusicService", "PLAYING: song id = OKCKR_pbHHk"),
            ProblemLog.Line("19:48:40.000", 'E', "Search ", "could not load (HTTP 400)"),
        )
        assertEquals("Search: could not load (HTTP 400)", ReportRelay.title(ProblemLog.Excerpt(lines, 1)))
        assertNull(ReportRelay.title(ProblemLog.Excerpt(lines, null)))
        assertNull(ReportRelay.title(null))
    }

    @Test
    fun `only InterTune's own builds send, and only with the flag`() {
        val relay = File("src/main/java/com/dd3boh/outertune/utils/ReportRelay.kt").readText()
        assertTrue(relay.contains("if (!BuiltInKeys.official) return@withContext Outcome.Failed("))
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/component/ProblemReportDialog.kt").readText()
        assertTrue(dialog.contains("val relay = Unreleased.REPORT_RELAY && BuiltInKeys.official"))
        // Without the flag the page is as it was: the form, Copy and Close.
        assertTrue(dialog.contains("if (!relay) TextButton(onClick = onDismiss) { Text(stringResource(R.string.problem_report_close)) }"))
        // The crash page sends the same way, under the same guard, and keeps its own buttons without it.
        val crash = File("src/main/java/com/dd3boh/outertune/ui/component/CrashReportDialog.kt").readText()
        assertTrue(crash.contains("val relay = Unreleased.REPORT_RELAY && BuiltInKeys.official"))
        assertTrue(crash.contains("if (!relay) TextButton(onClick = forget) { Text(stringResource(R.string.crash_report_dismiss)) }"))
        assertTrue(crash.contains("else if (BuiltInKeys.official) TextButton(onClick = openGitHub)"))
    }

    @Test
    fun `its words are there in English and in French`() {
        val names = listOf(
            "problem_report_send", "problem_report_contact", "problem_report_contact_hint", "problem_report_contact_note",
            "problem_report_sent", "problem_report_sent_body", "problem_report_open_issue", "problem_report_copy_link",
            "problem_report_held", "problem_report_send_failed", "problem_report_what",
        )
        for (folder in listOf("values", "values-fr")) {
            val texts = File("src/main/res/$folder/strings-ot.xml").readText()
            for (name in names) assertTrue("$folder: $name", texts.contains("name=\"$name\""))
        }
    }
}
