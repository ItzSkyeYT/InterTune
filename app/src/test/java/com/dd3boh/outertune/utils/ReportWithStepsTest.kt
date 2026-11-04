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

/**
 * A report with the settings and the steps before the problem in it (ProblemLog.report), and how it is
 * cut when it has to fit the bug form's link. Discord's paste limit and GitHub's link are where logs got
 * stuck before: what was done must survive the cut as the log does.
 */
class ReportWithStepsTest {
    private val log = List(40) { "10-13 18:00:%02d.000 D/MusicService( 6711): earlier line number $it of the forty that led up to it".format(it) } + listOf(
        "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711): Audio sink error",
        "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711):       at android.os.HandlerThread.run(HandlerThread.java:139)",
    )

    private val settings = listOf(
        "Settings (playback), * = not the default:",
        "* audio quality high",
        "* skip silence on",
        "At default: normalisation on, offload off,",
        "  equalizer off, spatial audio off,",
        "  transition fade off, skip on error off,",
        "  proxy off, battery use balanced",
        "Also: signed in, song cache 256 MB",
    )

    private val steps = ReportTrail.HEADER_FAULT + "\n" + listOf(
        "-3:20 opened settings/player", "-2:00 set skip silence to on", "-1:00 song started (picked)", "-0:40 seeked to 1:12",
        "-0:20 song started (skipped)", "-0:08 playback error: IO_BAD_HTTP_STATUS 403", "-0:08 network lost", "0:00 >>> here", "+0:05 network back",
    ).joinToString("\n")

    private fun report(withExtras: Boolean = true) = ProblemLog.report(
        ProblemLog.excerpt(log), build = "0.12.7 (97, core debug)", device = "Google Pixel 5, Android 14 (API 34)", installedFrom = "F-Droid",
        phoneNow = "Wi-Fi, not metered, no VPN", playback = "4 songs",
        settings = if (withExtras) settings else emptyList(), trail = if (withExtras) steps else null,
    )

    @Test
    fun `without the extras the report is as it was`() {
        val text = report(withExtras = false)
        assertTrue(
            text.startsWith(
                "InterTune 0.12.7 (97, core debug)\nGoogle Pixel 5, Android 14 (API 34)\nInstalled from: F-Droid\nPhone now: Wi-Fi, not metered, no VPN\n" +
                    "Playback this run: 4 songs\nThe last fault and what led up to it:\n\n"
            )
        )
        assertFalse(ReportTrail.HEADER_START in text)
    }

    @Test
    fun `the settings join the heading and the steps stand in a block of their own before the log`() {
        val text = report()
        val blocks = text.split("\n\n")
        assertEquals(
            "InterTune 0.12.7 (97, core debug)\nGoogle Pixel 5, Android 14 (API 34)\nInstalled from: F-Droid\nPhone now: Wi-Fi, not metered, no VPN\n" +
                "Playback this run: 4 songs\n" + settings.joinToString("\n"),
            blocks[0],
        )
        assertEquals(steps, blocks[1])
        assertEquals("The last fault and what led up to it:", blocks[2])
        assertTrue(blocks[3].contains(">>> here\n18:44:40.811 E/MediaCodecAudioRenderer: Audio sink error"))
    }

    @Test
    fun `a report that fits whole is not cut`() {
        val text = report()
        assertEquals(text, ProblemLog.fitted(text, text.length))
    }

    @Test
    fun `cut to fit, the heading, the steps nearest the fault and the end of the log stay`() {
        val text = report()
        for (limit in listOf(1400, 1800, 2300)) {
            val cut = ProblemLog.fitted(text, limit)
            assertTrue("$limit: ${cut.length}", cut.length <= limit)
            assertTrue("$limit: heading", cut.startsWith("InterTune 0.12.7 (97, core debug)\n"))
            assertTrue("$limit: settings", settings.all { it in cut })
            assertTrue("$limit: steps", ReportTrail.HEADER_FAULT in cut && "0:00 >>> here" in cut)
            assertTrue("$limit: the fault", cut.contains("Audio sink error"))
            assertTrue("$limit: the end", cut.endsWith("at android.os.HandlerThread.run(HandlerThread.java:139)"))
        }
    }

    @Test
    fun `the steps take no more than their share of what is left, the farthest from the fault going first`() {
        val text = report()
        // Room for the heading, the steps once and a bit more: their share of it is less than they take.
        val cut = ProblemLog.fitted(text, text.substringBefore("\n\n").length + 2 + steps.length * 2)
        val kept = cut.split("\n\n").first { it.startsWith(ReportTrail.HEADER_START) }
        assertTrue(kept.length < steps.length)
        // The marker and what is closest to it; the oldest step is the first to go.
        assertTrue("0:00 >>> here" in kept)
        assertTrue("-0:08 playback error: IO_BAD_HTTP_STATUS 403" in kept)
        assertFalse("-3:20 opened settings/player" in kept)
        // And the log below is cut from the front as it always was.
        assertTrue("(the earlier lines are left out)" in cut)
        assertFalse("earlier line number 0 " in cut)
    }

    @Test
    fun `cut for the form's link, the steps are in it and it still fits`() {
        val text = report()
        val budget = 4500 - 300
        val cut = ProblemLog.fittedForLink(text, budget, 5000)
        assertTrue("${ProblemLog.linkLength(cut)}", ProblemLog.linkLength(cut) <= budget)
        assertTrue(ReportTrail.HEADER_FAULT in cut)
        assertTrue("0:00 >>> here" in cut)
        assertTrue(cut.contains("Audio sink error"))
    }

    @Test
    fun `cut for a link, the log is kept around the fault and not at its end, where two dozen lines may follow it`() {
        val early = List(40) { "10-13 18:00:%02d.000 D/MusicService( 6711): earlier line number $it of the forty that led up to it".format(it) }
        val fault = listOf("10-13 18:44:40.811 E/MusicService( 6711): PLAYING: gave up on [aaaaaaaaaaa] after 3 tries: no byte from the server")
        val later = List(24) { "10-13 18:45:%02d.000 D/MusicService( 6711): ordinary line after the fault, number $it".format(it) }
        val text = ProblemLog.report(
            ProblemLog.excerpt(early + fault + later), build = "0.12.7 (97, core debug)", device = "Google Pixel 5, Android 14 (API 34)",
            installedFrom = "F-Droid", settings = settings, trail = steps, stops = listOf("11-02 16:00:00 STOPPED: paused by another app"),
        )
        for (limit in listOf(1900, 2400)) {
            val cut = ProblemLog.fitted(text, limit)
            assertTrue("$limit: ${cut.length}", cut.length <= limit)
            assertTrue("$limit: the fault's line is kept", "gave up on [aaaaaaaaaaa]" in cut)
            assertTrue("$limit: and the marker before it", "\n>>> here\n18:44:40.811 E/MusicService" in cut)
            assertTrue("$limit: some of what led up to it", "earlier line number 39" in cut)
            assertTrue("$limit: the steps and the settings", ReportTrail.HEADER_START in cut && settings.all { it in cut })
            assertTrue("$limit: what was left out is said", "(the later lines are left out)" in cut && "(the earlier lines are left out)" in cut)
            assertFalse("$limit: the last of the lines after it are not", "number 23" in cut)
            assertTrue("$limit: the stops stay at the end", cut.endsWith("STOPPED: paused by another app"))
        }
        // With room for all of it, nothing is left out.
        assertEquals(text, ProblemLog.fitted(text, text.length))
    }

    @Test
    fun `cut for the form's link, a report with a fault in a busy log still carries the fault`() {
        val early = List(40) { "10-13 18:00:%02d.000 D/MusicService( 6711): earlier line number $it of the forty that led up to it".format(it) }
        val fault = listOf("10-13 18:44:40.811 E/MusicService( 6711): PLAYING: gave up on [aaaaaaaaaaa] after 3 tries: no byte from the server")
        val later = List(24) { "10-13 18:45:%02d.000 D/MusicService( 6711): ordinary line after the fault, number $it".format(it) }
        val text = ProblemLog.report(
            ProblemLog.excerpt(early + fault + later), build = "0.12.7 (97, core debug)", device = "Google Pixel 5, Android 14 (API 34)",
            installedFrom = "F-Droid", phoneNow = "Wi-Fi, not metered, about 30 Mbps down and 12 Mbps up, no VPN, Data Saver off; battery saver off",
            settings = settings, trail = steps,
        )
        val budget = 4500 - 300
        val cut = ProblemLog.fittedForLink(text, budget, 5000)
        assertTrue("${ProblemLog.linkLength(cut)}", ProblemLog.linkLength(cut) <= budget)
        assertTrue(ReportTrail.HEADER_START in cut && "gave up on [aaaaaaaaaaa]" in cut)
    }

    @Test
    fun `a heading with its settings and steps is never all that is left of a report that does not fit`() {
        val text = report()
        // Barely more than the heading: nothing of the steps fits whole, and the log still gets what remains or nothing, but the heading stands.
        val cut = ProblemLog.fitted(text, text.indexOf("\n\n") + 30)
        assertTrue(cut.startsWith("InterTune 0.12.7 (97, core debug)\n"))
        assertTrue(settings.all { it in cut })
    }

    @Test
    fun `steps cut to a room keep the header and the lines nearest the marker`() {
        val block = steps
        assertEquals(block, ProblemLog.fittedSteps(block, block.length))
        val small = ProblemLog.fittedSteps(block, 200)!!
        assertTrue(small.length <= 200)
        assertTrue(small.startsWith(ReportTrail.HEADER_FAULT + "\n"))
        assertTrue("0:00 >>> here" in small)
        // Too little for the header and a line: nothing.
        assertEquals(null, ProblemLog.fittedSteps(block, ReportTrail.HEADER_FAULT.length + 5))
    }

    @Test
    fun `how long a typical report is`() {
        // Not a rule, a record: the length of this fixture, with and without the new parts. The real example is in the log of the work.
        val without = report(withExtras = false).length
        val with = report().length
        assertTrue("the extras add ${with - without} characters", with - without in 400..1600)
    }
}
