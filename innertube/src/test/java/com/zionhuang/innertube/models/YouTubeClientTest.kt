/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * toContext's hlOverride is what YTPlayerUtils.resolveOnce relies on to ask the fallback clients'
 * /player in English regardless of the app's content language, so Throttle's English-only
 * matchers can classify their refusal. ThrottleTest covers the matchers themselves; this covers
 * the plumbing that gets English text to them in the first place, which none of those tests would
 * notice going missing.
 */
class YouTubeClientTest {
    private val client = YouTubeClient.IOS

    @Test
    fun `hlOverride replaces the locale's own hl`() {
        val locale = YouTubeLocale(gl = "FR", hl = "fr")
        val context = client.toContext(locale, visitorData = null, dataSyncId = null, hlOverride = "en")
        assertEquals("en", context.client.hl)
        assertEquals("FR", context.client.gl)
    }

    @Test
    fun `no hlOverride keeps the locale's own hl`() {
        val locale = YouTubeLocale(gl = "FR", hl = "fr")
        val context = client.toContext(locale, visitorData = null, dataSyncId = null)
        assertEquals("fr", context.client.hl)
    }
}
