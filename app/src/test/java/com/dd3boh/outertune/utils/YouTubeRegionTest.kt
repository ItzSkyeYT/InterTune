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
import java.io.File

/**
 * #68 (1 Nov 2026): in South Korea YouTube shows songs, albums and mixes only to Premium members,
 * so search found only videos and an album opened on an empty page. YouTube decides by the
 * connection, and writes the country it saw into the visitor tokens it issues: the app reads it
 * there to know when to say so. The tokens below have YouTube's shape (a visitor id, a time, then
 * the place: the country and a blob) with a made-up visitor id.
 */
class YouTubeRegionTest {
    private val fr = "CgtUZXN0VmlzaXQwMSih9ZvXBjIoCgJGUhIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgQg=="
    private val kr = "CgtUZXN0VmlzaXQwMSih9ZvXBjIoCgJLUhIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgQg=="
    private val us = "CgtUZXN0VmlzaXQwMSih9ZvXBjIoCgJVUxIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgQg=="

    @Test
    fun `the country YouTube wrote into a token is read back`() {
        assertEquals("FR", YouTubeRegion.countryOf(fr))
        assertEquals("KR", YouTubeRegion.countryOf(kr))
        assertEquals("US", YouTubeRegion.countryOf(us))
    }

    @Test
    fun `a token as it travels in a URL, or without its padding, reads the same`() {
        assertEquals("KR", YouTubeRegion.countryOf(kr.replace("=", "%3D")))
        assertEquals("KR", YouTubeRegion.countryOf(kr.trimEnd('=')))
    }

    @Test
    fun `a token with no place in it, or no token at all, says nothing`() {
        assertNull(YouTubeRegion.countryOf("CgtUZXN0VmlzaXQwMSih9ZvXBg=="))
        assertNull(YouTubeRegion.countryOf(null))
        assertNull(YouTubeRegion.countryOf(""))
        assertNull(YouTubeRegion.countryOf("null"))
        assertNull(YouTubeRegion.countryOf("not a token!"))
        assertNull("cut short inside the place", YouTubeRegion.countryOf(kr.take(30)))
    }

    @Test
    fun `only a country with no free plan closes the catalogue`() {
        assertTrue(YouTubeRegion.closed("KR"))
        assertFalse(YouTubeRegion.closed("FR"))
        assertFalse(YouTubeRegion.closed("US"))
        assertFalse(YouTubeRegion.closed(null))
    }

    @Test
    fun `YouTube is asked only when something about the phone points to such a country`() {
        assertTrue(YouTubeRegion.hinted("Asia/Seoul", localeCountry = "US", networkCountry = "", simCountry = "", tokenCountry = null))
        assertTrue(YouTubeRegion.hinted("Europe/Paris", localeCountry = "KR", networkCountry = null, simCountry = null, tokenCountry = null))
        assertTrue(YouTubeRegion.hinted("Europe/Paris", localeCountry = "FR", networkCountry = "kr", simCountry = null, tokenCountry = null))
        assertTrue(YouTubeRegion.hinted("Europe/Paris", localeCountry = "FR", networkCountry = null, simCountry = "kr", tokenCountry = null))
        assertTrue(YouTubeRegion.hinted("America/New_York", localeCountry = "US", networkCountry = "us", simCountry = "us", tokenCountry = "KR"))
        assertFalse(YouTubeRegion.hinted("Europe/Paris", localeCountry = "FR", networkCountry = "fr", simCountry = "fr", tokenCountry = "FR"))
        assertFalse(YouTubeRegion.hinted(null, localeCountry = null, networkCountry = null, simCountry = null, tokenCountry = null))
    }

    @Test
    fun `the note stands where the catalogue is missed, and an album that did not load is never an empty page`() {
        val src = File("src/main/java/com/dd3boh/outertune")
        val album = File(src, "ui/screens/AlbumScreen.kt").readText()
        // Through FailedNote since the why-stopped notes (2 Nov 2026): the region's note over
        // Retry for a closed country, Retry alone when no cause is clear.
        assertTrue("the album page answers a failed load", album.contains("FailedNote(") && album.contains("onRetry = viewModel::retry"))
        val failed = File(src, "ui/component/WhyStoppedNote.kt").readText().substringAfter("fun FailedNote(")
        assertTrue(failed.contains("YouTubeRegion.closed("))
        assertTrue("the proxy switch is the way back", failed.contains("onProxy: () -> Unit = ::openProxySetting"))
        assertTrue(failed.contains("RegionNote(onProxy = onProxy, onDone = null"))
        assertTrue(failed.contains("LoadFailed(onRetry = onRetry"))
        assertTrue(File(src, "viewmodels/AlbumViewModel.kt").readText().contains("fun retry()"))
        assertTrue(File(src, "ui/screens/HomeScreen.kt").readText().contains("RegionNoteIfClosed("))
        assertTrue(File(src, "ui/screens/search/OnlineSearchResult.kt").readText().contains("RegionNoteIfClosed("))
        assertTrue(File(src, "App.kt").readText().contains("YouTubeRegion.refresh("))
        assertTrue(File(src, "MainActivity.kt").readText().contains("YouTubeRegion.pretendFrom(this)"))
        val region = File(src, "utils/YouTubeRegion.kt").readText()
        assertTrue("pretending is for debug builds only", region.contains("BuildConfig.DEBUG &&"))
    }
}
