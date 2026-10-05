/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Who a question or an announcement is for. The cost of a mistake here is a question meant for a
 * few phones reaching all of them, so what cannot be read hides the entry, and a name has to match
 * whole.
 */
class AudienceTest {

    private val s25 = DeviceFacts(sdk = 36, manufacturer = "samsung", brand = "samsung", model = "SM-S938B", device = "pa3q")
    private val pixel = DeviceFacts(sdk = 37, manufacturer = "Google", brand = "google", model = "Pixel 10 Pro XL", device = "mustang")
    private val keypad = DeviceFacts(sdk = 24, manufacturer = "Kyocera", brand = "KYOCERA", model = "KY-42C", device = "KY-42C")

    @Test
    fun `no audience is everyone`() {
        for (phone in listOf(s25, pixel, keypad)) assertTrue(Audience().includes(phone))
    }

    @Test
    fun `both ends of the Android range are included`() {
        val only14to16 = Audience(minSdk = 34, maxSdk = 36)
        assertTrue(only14to16.includes(s25.copy(sdk = 34)))
        assertTrue(only14to16.includes(s25.copy(sdk = 36)))
        assertFalse(only14to16.includes(s25.copy(sdk = 33)))
        assertFalse(only14to16.includes(s25.copy(sdk = 37)))
    }

    @Test
    fun `a device entry matches a manufacturer, a brand, a model or a code name`() {
        assertTrue(Audience(devices = listOf("samsung")).includes(s25))
        assertTrue(Audience(devices = listOf("SM-S938B")).includes(s25))
        assertTrue(Audience(devices = listOf("pa3q")).includes(s25))
        assertTrue(Audience(devices = listOf("kyocera")).includes(keypad))
        assertFalse(Audience(devices = listOf("samsung")).includes(pixel))
    }

    @Test
    fun `case and stray spaces do not matter`() {
        assertTrue(Audience(devices = listOf("  SAMSUNG ")).includes(s25))
        assertTrue(Audience(devices = listOf("pixel 10 pro xl")).includes(pixel))
    }

    @Test
    fun `manufacturer and model together are a name too`() {
        assertTrue(Audience(devices = listOf("google pixel 10 pro xl")).includes(pixel))
    }

    @Test
    fun `a star stands for any run of characters`() {
        assertTrue(Audience(devices = listOf("SM-S93*")).includes(s25))
        assertTrue(Audience(devices = listOf("pixel 10*")).includes(pixel))
        assertTrue(Audience(devices = listOf("*pro*")).includes(pixel))
        assertTrue(Audience(devices = listOf("pixel*xl")).includes(pixel))
        assertFalse(Audience(devices = listOf("pixel 9*")).includes(pixel))
    }

    @Test
    fun `a name has to match whole, not somewhere inside`() {
        // "pixel" alone must not catch every model that has the word in it by accident of spelling,
        // and "sm" must not catch every Samsung model number: the star is how a family is asked for.
        assertFalse(Audience(devices = listOf("pixel")).includes(pixel))
        assertFalse(Audience(devices = listOf("sm")).includes(s25))
        assertFalse(Audience(devices = listOf("sung")).includes(s25))
    }

    @Test
    fun `one matching entry is enough`() {
        assertTrue(Audience(devices = listOf("nothing", "samsung")).includes(s25))
        assertFalse(Audience(devices = listOf("nothing", "oneplus")).includes(s25))
    }

    @Test
    fun `devices and Android versions both have to hold`() {
        val audience = Audience(minSdk = 36, devices = listOf("samsung"))
        assertTrue(audience.includes(s25))
        assertFalse(audience.includes(s25.copy(sdk = 35)))
        assertFalse(audience.includes(pixel))
    }

    @Test
    fun `a blank entry matches nobody, even a phone that reports no names`() {
        val nameless = DeviceFacts(sdk = 30, manufacturer = "", brand = "", model = "", device = "")
        assertFalse(Audience(devices = listOf(" ")).includes(nameless))
        assertFalse(Audience(devices = listOf("*")).includes(nameless))
        assertTrue(Audience(devices = listOf("*")).includes(s25))
    }

    @Test
    fun `stars at the edges and in the middle`() {
        assertTrue(wildcardMatches("abc", "abc"))
        assertFalse(wildcardMatches("abc", "abcd"))
        assertTrue(wildcardMatches("a*c", "abc"))
        assertTrue(wildcardMatches("a*c", "ac"))
        assertFalse(wildcardMatches("a*a", "a"))
        assertTrue(wildcardMatches("*b*", "abc"))
        assertTrue(wildcardMatches("a**c", "abc"))
        assertFalse(wildcardMatches("a*b*c", "acb"))
    }

    // ---- as written in the document

    private val now = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
    private val minimal = """"id": "a", "banner": "B", "title": "T""""
    private fun shown(extra: String, phone: DeviceFacts) =
        AnnouncementParser.parse("""{"announcements": [{$minimal, $extra}]}""", 92, now, phone).isNotEmpty()

    @Test
    fun `an announcement for some Android versions shows only there`() {
        assertTrue(shown(""""minSdk": 34""", s25))
        assertFalse(shown(""""minSdk": 34""", keypad))
        assertTrue(shown(""""maxSdk": 28""", keypad))
        assertFalse(shown(""""maxSdk": 28""", s25))
    }

    @Test
    fun `an announcement for some devices shows only there`() {
        assertTrue(shown(""""devices": ["samsung", "pixel 10*"]""", s25))
        assertTrue(shown(""""devices": ["samsung", "pixel 10*"]""", pixel))
        assertFalse(shown(""""devices": ["samsung", "pixel 10*"]""", keypad))
    }

    @Test
    fun `an empty device list is everyone, like no list`() {
        assertTrue(shown(""""devices": []""", keypad))
        assertTrue(shown(""""devices": null, "minSdk": null""", keypad))
    }

    @Test
    fun `an audience that cannot be read hides the entry instead of showing it to everyone`() {
        for (phone in listOf(s25, keypad)) {
            assertFalse(shown(""""minSdk": "fourteen"""", phone))
            assertFalse(shown(""""maxSdk": 28.5""", phone))
            assertFalse(shown(""""devices": "samsung"""", phone))
            assertFalse(shown(""""devices": ["samsung", 7]""", phone))
            assertFalse(shown(""""devices": [""]""", phone))
        }
    }

    @Test
    fun `an entry with no audience reads as it always did`() {
        assertEquals(1, AnnouncementParser.parse("""{"announcements": [{$minimal}]}""", 92, now, keypad).size)
        assertEquals(1, AnnouncementParser.parse("""{"announcements": [{$minimal}]}""", 92, now).size)
    }

    @Test
    fun `a question's audience is read from its own text the same way`() {
        assertEquals(Audience(minSdk = 31, devices = listOf("samsung")), AnnouncementParser.audienceOfEntry("""{"id": "q", "minSdk": 31, "devices": ["samsung"]}"""))
        assertEquals(Audience(), AnnouncementParser.audienceOfEntry("""{"id": "q"}"""))
        assertNull(AnnouncementParser.audienceOfEntry("""{"id": "q", "devices": {"a": 1}}"""))
        assertNull(AnnouncementParser.audienceOfEntry("not json"))
    }
}
