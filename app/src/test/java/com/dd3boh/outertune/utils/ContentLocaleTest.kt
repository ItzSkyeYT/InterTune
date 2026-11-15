/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What YouTube is asked in (ContentLocale): the two "YouTube Music" rows, and on System default the
 * language the app speaks. A Dutch phone whose app is switched to English must get English from
 * YouTube as well, or the app is half one and half the other, and must keep Dutch charts.
 */
class ContentLocaleTest {

    private val dutchPhone = Locale.forLanguageTag("nl-NL")

    @Test
    fun `on system default YouTube answers in the language of the app, not the phone`() {
        val english = ContentLocale.of(SYSTEM_DEFAULT, SYSTEM_DEFAULT, Locale.forLanguageTag("en"), dutchPhone)
        assertEquals("en", english.hl)
        val french = ContentLocale.of(SYSTEM_DEFAULT, SYSTEM_DEFAULT, Locale.forLanguageTag("fr"), dutchPhone)
        assertEquals("fr", french.hl)
    }

    @Test
    fun `an app language with no country keeps the phone's country`() {
        // "English" has no country of its own: the charts of a Dutch phone stay Dutch.
        assertEquals("NL", ContentLocale.of(SYSTEM_DEFAULT, SYSTEM_DEFAULT, Locale.forLanguageTag("en"), dutchPhone).gl)
        assertEquals("NL", ContentLocale.of(null, null, Locale.forLanguageTag("de"), dutchPhone).gl)
    }

    @Test
    fun `an app language with a country, picked on Android's own page, brings its country`() {
        val french = ContentLocale.of(SYSTEM_DEFAULT, SYSTEM_DEFAULT, Locale.forLanguageTag("fr-FR"), dutchPhone)
        assertEquals("fr", french.hl)
        assertEquals("FR", french.gl)
    }

    @Test
    fun `a pick in either row wins over the app`() {
        val picked = ContentLocale.of("de", "AT", Locale.forLanguageTag("en"), dutchPhone)
        assertEquals("de", picked.hl)
        assertEquals("AT", picked.gl)
        // One row picked, the other on System default: each is worked out alone.
        val languageOnly = ContentLocale.of("de", SYSTEM_DEFAULT, Locale.forLanguageTag("fr-FR"), dutchPhone)
        assertEquals("de", languageOnly.hl)
        assertEquals("FR", languageOnly.gl)
        val countryOnly = ContentLocale.of(SYSTEM_DEFAULT, "BE", Locale.forLanguageTag("fr-FR"), dutchPhone)
        assertEquals("fr", countryOnly.hl)
        assertEquals("BE", countryOnly.gl)
    }

    @Test
    fun `what YouTube has no language or country for falls back as it always did`() {
        val nowhere = ContentLocale.of(null, null, Locale.forLanguageTag("xx"), Locale.forLanguageTag("xx"))
        assertEquals("en", nowhere.hl)
        assertEquals("US", nowhere.gl)
    }

    @Test
    fun `Chinese is asked for by its script-free name and Indonesian and Hebrew by YouTube's codes`() {
        assertEquals("zh-TW", ContentLocale.of(null, null, Locale.forLanguageTag("zh-Hant-TW"), dutchPhone).hl)
        assertEquals("zh-TW", ContentLocale.of(null, null, Locale.forLanguageTag("zh-TW"), dutchPhone).hl)
        assertEquals("zh-CN", ContentLocale.of(null, null, Locale.forLanguageTag("zh-CN"), dutchPhone).hl)
        assertEquals("id", ContentLocale.of(null, null, Locale("in"), dutchPhone).hl)
        assertEquals("id", ContentLocale.of(null, null, Locale.forLanguageTag("id"), dutchPhone).hl)
        assertEquals("iw", ContentLocale.of(null, null, Locale("iw"), dutchPhone).hl)
        assertEquals("iw", ContentLocale.of(null, null, Locale.forLanguageTag("he"), dutchPhone).hl)
    }

    @Test
    fun `the lyrics source is asked for Traditional Chinese only by an app in zh-TW, and not for ever`() {
        assertTrue(ContentLocale.traditionalChinese(Locale.forLanguageTag("zh-TW")))
        assertTrue(ContentLocale.traditionalChinese(Locale.forLanguageTag("zh-Hant-TW")))
        assertFalse(ContentLocale.traditionalChinese(Locale.forLanguageTag("zh-CN")))
        // It was only ever switched on: an app taken back out of zh-TW has to switch it off.
        assertFalse(ContentLocale.traditionalChinese(Locale.forLanguageTag("en")))
    }
}
