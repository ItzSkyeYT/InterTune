/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.artist

import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.DescriptionPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The About section of an artist's page: when it shows, and how its subscriber count reads. */
class ArtistAboutTextTest {

    private fun page(
        description: String? = null,
        subscribers: String? = null,
        monthly: String? = null,
    ) = ArtistPage(
        artist = ArtistItem(id = "UC1", title = "Someone", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null),
        sections = emptyList(),
        descriptionParts = listOfNotNull(description?.let { DescriptionPart(it) }),
        subscriberCount = subscribers,
        monthlyListeners = monthly,
    )

    @Test
    fun `the section shows when YouTube gives any one of its three parts`() {
        assertFalse(hasAbout(null))
        assertFalse(hasAbout(page()))
        assertTrue(hasAbout(page(description = "A duo from Paris.")))
        assertTrue(hasAbout(page(subscribers = "7.19M")))
        assertTrue(hasAbout(page(monthly = "74.4M monthly audience")))
    }

    @Test
    fun `a description of spaces alone is no description`() {
        assertFalse(hasAbout(page(description = " \n ")))
    }

    @Test
    fun `a count written out in full is its own number`() {
        assertEquals(1, subscriberQuantity("1"))
        assertEquals(0, subscriberQuantity("0"))
        assertEquals(523, subscriberQuantity("523"))
        assertEquals(1204, subscriberQuantity("1,204"))
        assertEquals(1204, subscriberQuantity("1 204"))
    }

    @Test
    fun `a short count is a thousand for K and a million for the larger sizes`() {
        assertEquals(1_000, subscriberQuantity("12K"))
        assertEquals(1_000, subscriberQuantity("12 k"))
        assertEquals(1_000_000, subscriberQuantity("7.19M"))
        assertEquals(1_000_000, subscriberQuantity("7,19 M"))
        assertEquals(1_000_000, subscriberQuantity("1.2B"))
        assertEquals(1_000_000, subscriberQuantity("1,2 Md"))
    }

    @Test
    fun `a count too long for a number does not throw`() {
        assertEquals(999_999_999, subscriberQuantity("999,999,999,999"))
    }

    private fun texts(folder: String) = File("src/main/res/$folder/strings-ot.xml").readText()

    private fun plural(folder: String, name: String): Map<String, String> {
        val block = Regex("<plurals name=\"$name\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .find(texts(folder))!!.groupValues[1]
        return Regex("<item quantity=\"([a-z]+)\">(.*?)</item>").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the subscriber count reads in English and in French, with each plural French needs`() {
        assertEquals(setOf("one", "other"), plural("values", "artist_subscribers").keys)
        val french = plural("values-fr", "artist_subscribers")
        assertEquals(setOf("one", "many", "other"), french.keys)
        assertEquals("%1\$s d\\'abonnés", french["many"])
        for (name in listOf("artist_about_more", "artist_about_less")) {
            assertTrue(name, "name=\"$name\"" in texts("values"))
            assertTrue(name, "name=\"$name\"" in texts("values-fr"))
        }
    }
}
