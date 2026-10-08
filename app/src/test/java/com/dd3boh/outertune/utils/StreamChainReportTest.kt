/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What StreamChainProbe says of its answers, without asking anybody anything. */
class StreamChainReportTest {
    private val videos = listOf("fJ9rUzIMcZQ", "djV11Xbc914")

    /** What the chain answered on 8 Oct 2026. */
    private val refused = "LOGIN_REQUIRED \"Sign in to confirm you’re not a bot\", loudness no, details no, history address no"
    private val whole = "OK, HEAD 200, bytes past 512 KB served, loudness yes, details yes, history address yes"
    private val capped = "OK, HEAD 403, bytes past 512 KB refused (403), loudness yes, details yes, history address yes"
    private val today = listOf("ANDROID_VR: $refused", "VISIONOS: $whole", "IOS: $capped")

    @Test
    fun `a client that answered every song alike gets one line, one that did not gets each song's`() {
        val said = mapOf("ANDROID_VR" to listOf(refused, refused), "VISIONOS" to listOf(whole, capped))
        assertEquals(
            listOf("ANDROID_VR: $refused", "VISIONOS: fJ9rUzIMcZQ: $whole; djV11Xbc914: $capped"),
            StreamChainReport.lines(videos, said),
        )
    }

    @Test
    fun `the same answers as last time say SAME`() {
        val report = StreamChainReport.against(today, today, reached = true)
        assertEquals(today + "SAME", report.printed)
        assertEquals(today, report.keep)
    }

    @Test
    fun `an answer that changed says CHANGED and what it was`() {
        // The day VISIONOS is refused as well.
        val now = listOf("ANDROID_VR: $refused", "VISIONOS: $refused", "IOS: $capped")
        val report = StreamChainReport.against(today, now, reached = true)
        assertEquals(
            listOf("ANDROID_VR: $refused", "VISIONOS: $refused", "  was: $whole", "IOS: $capped", "CHANGED"),
            report.printed,
        )
        assertEquals(now, report.keep)
    }

    @Test
    fun `a first run has nothing to compare with and keeps what it found`() {
        for (kept in listOf(null, emptyList<String>())) {
            val report = StreamChainReport.against(kept, today, reached = true)
            assertEquals(today + "nothing was kept to compare with" + "CHANGED", report.printed)
            assertEquals(today, report.keep)
        }
    }

    @Test
    fun `a run that could not reach YouTube is no news and keeps what was there`() {
        val dead = listOf("ANDROID_VR: no answer (UnknownHostException)", "VISIONOS: no answer (UnknownHostException)")
        val report = StreamChainReport.against(today, dead, reached = false)
        assertEquals("SAME", report.printed.last())
        assertEquals(dead, report.printed.take(2))
        assertNull(report.keep)
    }

    @Test
    fun `a chain with a client more or a client fewer says CHANGED`() {
        val more = today + "ANDROID (asked without the account): $capped"
        val added = StreamChainReport.against(today, more, reached = true)
        assertEquals(today + more.last() + "  was: not in the chain" + "CHANGED", added.printed)
        val removed = StreamChainReport.against(more, today, reached = true)
        assertEquals(today + "no longer in the chain: ANDROID (asked without the account)" + "CHANGED", removed.printed)
        assertEquals(today, removed.keep)
    }
}
