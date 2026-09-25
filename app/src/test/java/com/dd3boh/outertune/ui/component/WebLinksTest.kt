/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class WebLinksTest {

    @Test
    fun `a bare invite becomes an https link, without the full stop after it`() {
        val text = "Type discord.gg/68jmqhMjXk into your browser to join."
        val links = webLinks(text)
        assertEquals(1, links.size)
        val (range, url) = links.single()
        assertEquals("discord.gg/68jmqhMjXk", text.substring(range))
        assertEquals("https://discord.gg/68jmqhMjXk", url)
    }

    @Test
    fun `a full address is kept as written and loses the sentence's punctuation`() {
        val text = "Read https://github.com/ItzSkyeYT/InterTune/wiki. Then decide."
        val (range, url) = webLinks(text).single()
        assertEquals("https://github.com/ItzSkyeYT/InterTune/wiki", url)
        assertEquals(url, text.substring(range))
    }

    @Test
    fun `text with no address and other schemes give nothing to tap`() {
        assertEquals(emptyList<Pair<IntRange, String>>(), webLinks("Pick the one you would want most."))
        assertEquals(emptyList<Pair<IntRange, String>>(), webLinks("javascript:alert(1) or intent:#Intent;end"))
    }

    @Test
    fun `two addresses in one line are both found`() {
        val urls = webLinks("Chat on discord.gg/abc or read https://example.com/a").map { it.second }
        assertEquals(listOf("https://discord.gg/abc", "https://example.com/a"), urls)
    }
}
