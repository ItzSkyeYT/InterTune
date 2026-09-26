/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cases webLinks() got wrong when it first shipped: the case of the scheme, punctuation beyond plain
 * ASCII, brackets, other schemes, and names that have the shape of an address.
 */
class WebLinksEdgeCasesTest {

    private val none = emptyList<Pair<IntRange, String>>()

    // Android matches intent-filter schemes case-sensitively, so ACTION_VIEW on "Https://..." finds
    // no browser, AndroidUriHandler throws, and the runCatching around the tap swallows it: the tap
    // does nothing. Autocorrect capitalises the first word of a line, so "Https://" is easy to get
    // in hand-written text.
    @Test
    fun `a capitalised scheme opens as lowercase https`() {
        val (_, url) = webLinks("Https://discord.gg/abc").single()
        assertEquals("https://discord.gg/abc", url)
    }

    @Test
    fun `an upper case scheme opens as lowercase https`() {
        val (_, url) = webLinks("HTTPS://EXAMPLE.COM/X").single()
        assertEquals("https://EXAMPLE.COM/X", url)
    }

    // A closing curly quote, which any document editor puts in by itself, is not part of the
    // address.
    @Test
    fun `a closing curly quote stays out of the address`() {
        val text = "Join us at “https://discord.gg/abc”."
        val (range, url) = webLinks(text).single()
        assertEquals("https://discord.gg/abc", url)
        assertEquals("https://discord.gg/abc", text.substring(range))
    }

    @Test
    fun `an ellipsis character after an address stays out of it`() {
        val (_, url) = webLinks("More soon at discord.gg/abc…").single()
        assertEquals("https://discord.gg/abc", url)
    }

    @Test
    fun `a closing square bracket stays out of the address`() {
        val text = "[discord.gg/abc]"
        val (range, url) = webLinks(text).single()
        assertEquals("https://discord.gg/abc", url)
        assertEquals("discord.gg/abc", text.substring(range))
    }

    // An address whose own last character is a closing parenthesis keeps it, or it opens a page
    // that does not exist.
    @Test
    fun `a parenthesis that belongs to the address is kept`() {
        val text = "See https://en.wikipedia.org/wiki/Opus_(audio_format) for details."
        val (_, url) = webLinks(text).single()
        assertEquals("https://en.wikipedia.org/wiki/Opus_(audio_format)", url)
    }

    // A document that said intent: must not become something to tap. With a host and a path, the
    // part after "intent://" has the shape of a bare address, and linking it would open, as https,
    // a link the text never had.
    @Test
    fun `an intent address with a host gives nothing to tap`() {
        assertEquals(none, webLinks("intent://example.com/x#Intent;scheme=https;end"))
    }

    @Test
    fun `an ftp address gives nothing to tap`() {
        assertEquals(none, webLinks("ftp://files.example.com/pub/file.zip"))
    }

    // "Last.fm/YouTube" is the kind of thing a poll in a music app says. A dotted name, a slash and
    // a word has the shape of an address, but a bare address is typed in lower case.
    @Test
    fun `a service name, a slash and another name is not an address`() {
        assertEquals(none, webLinks("Should similar songs come from Last.fm/YouTube?"))
    }

    // The end of something that is not a web address, or of a name written with capitals, is not
    // one either.
    @Test
    fun `the end of an email address, a longer name or another scheme is not an address`() {
        assertEquals(none, webLinks("someone@discord.gg/abc"))
        assertEquals(none, webLinks("Example.co.uk/x"))
        assertEquals(none, webLinks("Foo-bar.com/x"))
        assertEquals(none, webLinks("intent:example.com/x"))
    }

    @Test
    fun `a scheme with nothing after it gives nothing to tap`() {
        assertEquals(none, webLinks("Addresses start with https://."))
    }

    @Test
    fun `a bracket the address closes stays when another closes the sentence`() {
        val text = "(See https://en.wikipedia.org/wiki/Opus_(audio_format))."
        val (range, url) = webLinks(text).single()
        assertEquals("https://en.wikipedia.org/wiki/Opus_(audio_format)", url)
        assertEquals(url, text.substring(range))
    }
}
