/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Recap opened" is counted as a banner's first opening is: a bare number on the dashboard. The
 * sending itself (PollChecker) runs on org.json and the network, which the JVM tests stub, so its
 * rules are read from the source, as ReleasedFlagsTest reads the flags.
 */
class RecapCountTest {
    private val pollChecker = File("src/main/java/com/dd3boh/outertune/utils/PollChecker.kt").readText()

    /** The body of the function [name] in PollChecker, up to the next one. */
    private fun body(name: String): String {
        val start = pollChecker.indexOf("fun $name(")
        assertTrue("PollChecker has no $name", start >= 0)
        val next = Regex("""\n    (suspend |private |internal )*fun """).find(pollChecker, start + 1)?.range?.first ?: pollChecker.length
        return pollChecker.substring(start, next)
    }

    @Test
    fun `the count carries the four fields of a banner's first opening, and no id`() {
        val data = RecapCount.data(2026, "0.13")
        assertEquals(listOf("poll", "poll_id", "type", "version"), data.keys.toList())
        assertEquals("InterTune Recap 2026", data["poll"])
        assertEquals("recap-2026", data["poll_id"])
        assertEquals("recap", data["type"])
        assertFalse("id" in data)
    }

    @Test
    fun `it is sent as a banner's opening is, once per install, from every install`() {
        val recap = body("recapOpened")
        val banner = body("viewed")
        // The same event, written down first through the same record of what was opened.
        assertTrue("Polls.UMAMI_VIEW_EVENT" in recap && "Polls.UMAMI_VIEW_EVENT" in banner)
        assertTrue("firstOpening(RecapCount.id(year))" in recap)
        assertTrue("firstOpening(id)" in banner)
        // Counted for everyone, not only with News from InterTune switched on (24 Oct 2026).
        assertFalse("the Recap's count asks for news", "AnnouncementsEnabledKey" in recap || "PollsEnabledKey" in recap)
        // Not in a build without the maintainer's keys.
        assertTrue("Polls.isConfigured" in recap)
        // Nothing but RecapCount's fields: no install's name, as the usage count sends.
        assertFalse(".put(\"id\"" in recap)
        assertTrue("RecapCount.data(year, BuildConfig.VERSION_NAME)" in recap)
    }

    @Test
    fun `the same send as every other event, with the fixed User-Agent and no id`() {
        val send = body("send")
        assertTrue(".header(\"User-Agent\", USER_AGENT)" in send)
        assertFalse(".put(\"id\"" in send)
        assertTrue("\${Polls.UMAMI_URL}/api/send" in send)
    }
}
