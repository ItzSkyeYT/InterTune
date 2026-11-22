/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sign-in pages shown inside the app load no ads and no trackers (PageFilter). 22 Nov 2026: /e/OS
 * counted nine trackers against InterTune, last.fm's ad networks loaded by the Last.fm sign-in page.
 */
class PageFilterTest {

    /** A host for each of the nine Advanced Privacy named, as their tags and pixels load them. */
    private val nine = mapOf(
        "Adometry" to "d.adometry.com",
        "Amazon Advertising" to "aax.amazon-adsystem.com",
        "Google AdServices" to "www.googleadservices.com",
        "Google Syndication" to "pagead2.googlesyndication.com",
        "Google Tag" to "www.googletagmanager.com",
        "Integral Ad Science" to "pixel.adsafeprotected.com",
        "Media.net" to "contextual.media.net",
        "pub.network" to "a.pub.network",
        "TradeDesk" to "js.adsrvr.org",
    )

    @Test
    fun `the Last_fm page loads none of the nine, nor anything else off last_fm`() {
        for ((name, host) in nine) assertFalse(name, PageFilter.lastFmAllows("https", host, "/x.js"))
        for (host in listOf("cdn.snigelweb.com", "oss.maxcdn.com", "x.com", "www.facebook.com", "googleads.g.doubleclick.net")) {
            assertFalse(host, PageFilter.lastFmAllows("https", host, "/"))
        }
    }

    @Test
    fun `the Last_fm page loads last_fm, its pictures and a captcha if asked`() {
        assertTrue(PageFilter.lastFmAllows("https", "www.last.fm", "/api/auth/"))
        assertTrue(PageFilter.lastFmAllows("https", "last.fm", "/login"))
        assertTrue(PageFilter.lastFmAllows("https", "WWW.LAST.FM", "/static/css/main.css"))
        assertTrue(PageFilter.lastFmAllows("https", "lastfm.freetls.fastly.net", "/i/u/64s/a.png"))
        assertTrue(PageFilter.lastFmAllows("https", "www.google.com", "/recaptcha/api.js"))
        assertTrue(PageFilter.lastFmAllows("https", "www.gstatic.com", "/recaptcha/releases/x/recaptcha__en.js"))
        assertTrue("not the web: nothing to look up", PageFilter.lastFmAllows("data", null, null))
    }

    @Test
    fun `look-alike hosts and the rest of Google are refused on the Last_fm page`() {
        assertFalse(PageFilter.lastFmAllows("https", "notlast.fm", "/"))
        assertFalse(PageFilter.lastFmAllows("https", "last.fm.example.com", "/"))
        assertFalse(PageFilter.lastFmAllows("https", "www.google.com", "/search"))
        assertFalse(PageFilter.lastFmAllows("https", "www.google.com", "/pagead/1p-user-list/"))
        assertFalse(PageFilter.lastFmAllows("https", null, "/"))
    }

    @Test
    fun `the YouTube Music sign-in keeps Google's own hosts and loses the trackers`() {
        for ((name, host) in nine) assertTrue(name, PageFilter.isTracker(host))
        for (host in listOf("googleads.g.doubleclick.net", "static.doubleclick.net", "adservice.google.com", "www.google-analytics.com")) {
            assertTrue(host, PageFilter.isTracker(host))
        }
        for (host in listOf("accounts.google.com", "music.youtube.com", "www.youtube.com", "www.google.com", "www.gstatic.com",
            "fonts.googleapis.com", "apis.google.com", "lh3.googleusercontent.com", "i.ytimg.com", "ssl.gstatic.com")) {
            assertFalse(host, PageFilter.isTracker(host))
        }
        assertFalse(PageFilter.isTracker(null))
    }

    @Test
    fun `both sign-in pages ask the filter before anything loads`() {
        val screens = File("src/main/java/com/dd3boh/outertune/ui/screens")
        val lastFm = File(screens, "LastFmLoginScreen.kt").readText()
        assertTrue(lastFm.contains("override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =\n                            PageFilter.forLastFm(request)"))
        val youTube = File(screens, "LoginScreen.kt").readText()
        assertTrue(youTube.contains("override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =\n                                    PageFilter.forSignIn(request)"))
    }
}
