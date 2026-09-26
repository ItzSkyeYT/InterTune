/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases WebLinksTest does not pin, here so that a change to the pattern or to the punctuation left
 * out of an address cannot quietly break them.
 */
class WebLinksCoverageTest {

    private val none = emptyList<Pair<IntRange, String>>()

    @Test
    fun `things with dots that are not addresses give nothing to tap`() {
        assertEquals(none, webLinks("a.b"))
        assertEquals(none, webLinks("Pick one, e.g. the first."))
        assertEquals(none, webLinks("Fixed in version 0.11.5."))
        assertEquals(none, webLinks("Attach file.txt please"))
        assertEquals(none, webLinks("mailto:x@y.z"))
    }

    @Test
    fun `an address in parentheses loses the closing one`() {
        val text = "(https://x.yz/z)"
        val (range, url) = webLinks(text).single()
        assertEquals("https://x.yz/z", url)
        assertEquals(url, text.substring(range))
    }

    @Test
    fun `a comma and a straight closing quote stay out`() {
        val text = "Join discord.gg/abc, or \"discord.gg/def\" or 'discord.gg/ghi'."
        val links = webLinks(text)
        assertEquals(
            listOf("https://discord.gg/abc", "https://discord.gg/def", "https://discord.gg/ghi"),
            links.map { it.second },
        )
        assertEquals(listOf("discord.gg/abc", "discord.gg/def", "discord.gg/ghi"), links.map { text.substring(it.first) })
    }

    @Test
    fun `query strings and fragments are kept, the full stop is not`() {
        val text = "Vote at https://example.com/poll?id=3&x=y#results. Or example.com/p?q=1#f!"
        val links = webLinks(text)
        assertEquals(
            listOf("https://example.com/poll?id=3&x=y#results", "https://example.com/p?q=1#f"),
            links.map { it.second },
        )
    }

    @Test
    fun `a full http address is kept as written`() {
        val (_, url) = webLinks("Old mirror: http://example.com/x").single()
        assertEquals("http://example.com/x", url)
    }

    @Test
    fun `ranges are in order, do not overlap and cover only the address`() {
        val text = "a discord.gg/x. b https://e.com/y) c"
        val links = webLinks(text)
        assertEquals(2, links.size)
        assertEquals("discord.gg/x", text.substring(links[0].first))
        assertEquals("https://e.com/y", text.substring(links[1].first))
        assertTrue(links[0].first.last < links[1].first.first)
    }
}
