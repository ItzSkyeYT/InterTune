/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/**
 * The announcement document is written by hand, so these pin what a mistake in it does: leave that
 * one entry out, never the rest, and never show something at the wrong time or open the wrong kind
 * of address.
 */
class AnnouncementParserTest {

    private val now = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()

    private fun parse(entries: String, versionCode: Int = 89) =
        AnnouncementParser.parse("""{"announcements": [$entries]}""", versionCode, now)

    private fun one(entry: String, versionCode: Int = 89) = parse(entry, versionCode).single()

    private val minimal = """"id": "a", "banner": "B", "title": "T""""

    // The document the app has been reading since 0.10.9.5, unchanged, must read the same.
    @Test
    fun `a document in the old shape reads as before`() {
        val a = one(
            """{"id": "discord", "banner": "Join us", "title": "Discord", "body": "Come say hi",
               "image": "https://example.com/d.png", "actionLabel": "Join", "actionUrl": "https://discord.gg/abc",
               "minVersionCode": 89, "expiresAt": ${now + 1000}}"""
        )
        assertEquals("discord", a.id)
        assertEquals("Join us", a.text.banner)
        assertEquals("Discord", a.text.title)
        assertEquals("Come say hi", a.text.body)
        assertEquals("https://example.com/d.png", a.heroUrl)
        assertEquals(listOf(AnnouncementAction("Join", "https://discord.gg/abc")), a.text.actions)
        assertTrue(a.gallery.isEmpty())
    }

    @Test
    fun `an old single button needs both its label and its address`() {
        assertTrue(one("""{$minimal, "actionLabel": "Join"}""").text.actions.isEmpty())
        assertTrue(one("""{$minimal, "actionUrl": "https://discord.gg/abc"}""").text.actions.isEmpty())
    }

    @Test
    fun `id, banner and title are required, and one bad entry leaves the rest`() {
        val list = parse(
            """{"banner": "B", "title": "T"}, {"id": "x", "title": "T"}, {"id": "y", "banner": "B"},
               {"id": "  ", "banner": "B", "title": "T"}, "not an object", 7, {$minimal}"""
        )
        assertEquals(listOf("a"), list.map { it.id })
    }

    @Test
    fun `a number is accepted as an id`() {
        assertEquals("3", one("""{"id": 3, "banner": "B", "title": "T"}""").id)
    }

    @Test
    fun `a document that is not json, or has no list, gives nothing`() {
        assertTrue(AnnouncementParser.parse("not json", 89, now).isEmpty())
        assertTrue(AnnouncementParser.parse("""{"polls": []}""", 89, now).isEmpty())
        assertTrue(AnnouncementParser.parse("""{"announcements": {}}""", 89, now).isEmpty())
        assertTrue(AnnouncementParser.parse("""[]""", 89, now).isEmpty())
    }

    @Test
    fun `null counts as absent`() {
        val a = one("""{$minimal, "body": null, "image": null, "expiresAt": null, "minVersionCode": null}""")
        assertNull(a.text.body)
        assertNull(a.heroUrl)
    }

    @Test
    fun `version bounds apply, and an unreadable bound drops the entry`() {
        assertEquals(1, parse("""{$minimal, "minVersionCode": 89, "maxVersionCode": 89}""").size)
        assertTrue(parse("""{$minimal, "minVersionCode": 90}""").isEmpty())
        assertTrue(parse("""{$minimal, "maxVersionCode": 88}""").isEmpty())
        assertTrue(parse("""{$minimal, "minVersionCode": "89"}""").isEmpty())
    }

    @Test
    fun `nothing shows before it starts or after it expires`() {
        assertTrue(parse("""{$minimal, "startsAt": ${now + 1}}""").isEmpty())
        assertEquals(1, parse("""{$minimal, "startsAt": $now}""").size)
        assertTrue(parse("""{$minimal, "expiresAt": $now}""").isEmpty())
        assertEquals(1, parse("""{$minimal, "expiresAt": ${now + 1}}""").size)
        // Zero has always meant no expiry.
        assertEquals(1, parse("""{$minimal, "expiresAt": 0}""").size)
    }

    @Test
    fun `dates can be written out`() {
        // A date alone starts at the start of the day and expires at the end of it, UTC.
        assertEquals(1, parse("""{$minimal, "startsAt": "2026-09-26"}""").size)
        assertTrue(parse("""{$minimal, "startsAt": "2026-09-27"}""").isEmpty())
        assertEquals(1, parse("""{$minimal, "expiresAt": "2026-09-26"}""").size)
        assertTrue(parse("""{$minimal, "expiresAt": "2026-09-25"}""").isEmpty())
        // A full time, with a zone, an offset or neither (UTC).
        assertTrue(parse("""{$minimal, "startsAt": "2026-09-26T12:00:01Z"}""").isEmpty())
        assertEquals(1, parse("""{$minimal, "startsAt": "2026-09-26T13:59:00+02:00"}""").size)
        assertTrue(parse("""{$minimal, "expiresAt": "2026-09-26T11:59"}""").isEmpty())
    }

    @Test
    fun `a date nobody can read drops the entry rather than being ignored`() {
        assertTrue(parse("""{$minimal, "expiresAt": "31/10/2026"}""").isEmpty())
        assertTrue(parse("""{$minimal, "startsAt": "soon"}""").isEmpty())
        assertTrue(parse("""{$minimal, "expiresAt": {"day": 1}}""").isEmpty())
    }

    @Test
    fun `a timestamp in seconds is read as seconds`() {
        val seconds = now / 1000
        assertTrue(parse("""{$minimal, "expiresAt": ${seconds - 60}}""").isEmpty())
        assertEquals(1, parse("""{$minimal, "expiresAt": ${seconds + 60}}""").size)
    }

    @Test
    fun `buttons need a label and a web address, and there are at most three`() {
        val a = one(
            """{$minimal, "actions": [
                {"label": "One", "url": "https://one.example/x"},
                {"label": "No address"},
                {"url": "https://nolabel.example"},
                {"label": "Script", "url": "javascript:alert(1)"},
                {"label": "Intent", "url": "intent://scan#Intent;end"},
                {"label": "Two", "url": "HTTPS://two.example/Path"},
                {"label": "Three", "url": "discord.gg/abc"},
                {"label": "Four", "url": "https://four.example"}
            ]}"""
        )
        assertEquals(
            listOf(
                AnnouncementAction("One", "https://one.example/x"),
                AnnouncementAction("Two", "https://two.example/Path"),
                AnnouncementAction("Three", "https://discord.gg/abc"),
            ),
            a.text.actions,
        )
    }

    @Test
    fun `actions win over the old single button`() {
        val a = one(
            """{$minimal, "actionLabel": "Old", "actionUrl": "https://old.example",
               "actions": [{"label": "New", "url": "https://new.example"}]}"""
        )
        assertEquals(listOf("New"), a.text.actions.map { it.label })
    }

    @Test
    fun `web addresses only, with the scheme in lower case`() {
        assertEquals("https://discord.gg/abc", AnnouncementParser.webUrl("Https://discord.gg/abc"))
        assertEquals("http://example.com/A", AnnouncementParser.webUrl(" http://example.com/A "))
        assertEquals("https://example.com", AnnouncementParser.webUrl("example.com"))
        assertNull(AnnouncementParser.webUrl("file:///sdcard/x.png"))
        assertNull(AnnouncementParser.webUrl("content://media/1"))
        assertNull(AnnouncementParser.webUrl("https://"))
        assertNull(AnnouncementParser.webUrl("Example.com/x"))
        assertNull(AnnouncementParser.webUrl("two words.com"))
    }

    @Test
    fun `the first picture is the header, the rest a row`() {
        val a = one(
            """{$minimal, "image": "https://e.x/top.png",
               "images": ["https://e.x/1.png", "https://e.x/top.png", "file:///x.png", 5, "https://e.x/2.png", "https://e.x/1.png"]}"""
        )
        assertEquals("https://e.x/top.png", a.heroUrl)
        assertEquals(listOf("https://e.x/1.png", "https://e.x/2.png"), a.gallery)
    }

    @Test
    fun `without an image the first of images is the header`() {
        val a = one("""{$minimal, "images": ["https://e.x/1.png", "https://e.x/2.png"]}""")
        assertEquals("https://e.x/1.png", a.heroUrl)
        assertEquals(listOf("https://e.x/2.png"), a.gallery)
    }

    @Test
    fun `an image that is not a web address is left out`() {
        assertNull(one("""{$minimal, "image": "file:///sdcard/x.png"}""").heroUrl)
    }

    @Test
    fun `pictures stop at ten`() {
        val urls = (1..14).joinToString { "\"https://e.x/$it.png\"" }
        val a = one("""{$minimal, "images": [$urls]}""")
        assertEquals(AnnouncementParser.MAX_PICTURES, 1 + a.gallery.size)
    }

    @Test
    fun `a translation fills its gaps from the document, and borrows button addresses`() {
        val a = one(
            """{"id": "a", "banner": "Join us", "title": "Discord", "body": "Hi",
               "actions": [{"label": "Join", "url": "https://discord.gg/abc"}, {"label": "Read", "url": "https://e.x/r"}],
               "translations": {
                 "fr": {"banner": "Rejoignez-nous", "actions": [{"label": "Rejoindre"}, {"label": "Lire", "url": "https://e.x/fr"}]},
                 "de": {"title": "Discord!"}
               }}"""
        )
        val fr = a.textFor(listOf(Locale.FRANCE))
        assertEquals("Rejoignez-nous", fr.banner)
        assertEquals("Discord", fr.title)
        assertEquals("Hi", fr.body)
        assertEquals(
            listOf(AnnouncementAction("Rejoindre", "https://discord.gg/abc"), AnnouncementAction("Lire", "https://e.x/fr")),
            fr.actions,
        )
        // No buttons in the translation: the document's own.
        assertEquals(a.text.actions, a.textFor(listOf(Locale.GERMANY)).actions)
        assertEquals("Discord!", a.textFor(listOf(Locale.GERMANY)).title)
    }

    @Test
    fun `the closest language wins, then the next preference, then the document`() {
        val a = one(
            """{$minimal, "translations": {
                 "pt": {"title": "pt"}, "pt_BR": {"title": "pt-BR"}, "IW": {"title": "he"}, "zz-top-longer-than-eight": {"title": "bad"}
               }}"""
        )
        assertEquals("pt-BR", a.textFor(listOf(Locale.forLanguageTag("pt-BR"))).title)
        assertEquals("pt", a.textFor(listOf(Locale.forLanguageTag("pt-PT"))).title)
        assertEquals("pt", a.textFor(listOf(Locale.JAPAN, Locale.forLanguageTag("pt"))).title)
        assertEquals("T", a.textFor(listOf(Locale.JAPAN)).title)
        // Hebrew, whichever of its two codes the phone and the document use.
        assertEquals("he", a.textFor(listOf(Locale.forLanguageTag("he-IL"))).title)
        assertEquals("he", a.textFor(listOf(Locale("iw", "IL"))).title)
    }

    @Test
    fun `language keys are normalised`() {
        assertEquals("pt-br", AnnouncementParser.languageKey("pt_BR"))
        assertEquals("fr", AnnouncementParser.languageKey(" FR "))
        assertEquals("he", AnnouncementParser.languageKey("iw"))
        assertEquals("id-id", AnnouncementParser.languageKey("in-ID"))
        assertNull(AnnouncementParser.languageKey("français"))
        assertNull(AnnouncementParser.languageKey(""))
    }

    @Test
    fun `text is trimmed and blank text is absent`() {
        val a = one("""{"id": "a", "banner": "  B  ", "title": "T", "body": "   "}""")
        assertEquals("B", a.text.banner)
        assertNull(a.text.body)
    }
}
