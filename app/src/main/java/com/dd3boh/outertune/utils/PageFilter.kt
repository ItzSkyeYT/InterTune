/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * What the sign-in pages shown inside the app may load.
 *
 * 22 Nov 2026: /e/OS's Advanced Privacy counted nine trackers against InterTune (Adometry, Amazon
 * Advertising, Google AdServices, Google Syndication, Google Tag, Integral Ad Science, Media.net,
 * pub.network, TradeDesk). They were last.fm's ad networks: the Last.fm sign-in shows last.fm's own
 * page in a WebView, and that page loads Freestar (pub.network), Snigel, Amazon's ad system and
 * Google Tag Manager, whose auctions bring in the rest. Whatever a page in the app loads counts as the
 * app's. A refused request gets an empty answer before any lookup is made, so nothing reaches those
 * hosts at all.
 *
 * - The Last.fm page loads last.fm alone ([lastFmAllows]): its pages and its picture server, and
 *   Google's captcha in case last.fm asks for one at sign-in. Links that lead off last.fm show
 *   nothing.
 * - The YouTube Music sign-in needs Google's own hosts, so there the ad and tracking hosts are
 *   refused by name ([isTracker]) and everything else loads as before.
 */
object PageFilter {

    private const val TAG = "PageFilter"

    /** Last.fm's own hosts, the host itself or any under it. */
    private val LAST_FM = listOf("last.fm", "lastfm.freetls.fastly.net")

    /** Google's captcha, only under these paths. */
    private val CAPTCHA_HOSTS = setOf("www.google.com", "www.gstatic.com", "www.recaptcha.net")

    /**
     * Ad and tracking hosts, each the host itself or any under it: Google's ads, tags and analytics,
     * and the ad networks last.fm's page auctions its space to.
     */
    private val TRACKERS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagmanager.com",
        "googletagservices.com", "google-analytics.com", "analytics.google.com", "adservice.google.com",
        "app-measurement.com", "pub.network", "freestar.com", "freestar.io", "snigelweb.com",
        "amazon-adsystem.com", "adsrvr.org", "media.net", "adsafeprotected.com", "integralads.com",
        "adometry.com", "scorecardresearch.com", "quantserve.com", "criteo.com", "criteo.net",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com",
        "taboola.com", "outbrain.com", "facebook.net", "hotjar.com",
    )

    private fun under(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    /** Whether the Last.fm sign-in page may load this address. Anything that is not the web goes through. */
    fun lastFmAllows(scheme: String?, host: String?, path: String?): Boolean {
        if (scheme != "https" && scheme != "http") return true
        val h = host?.lowercase() ?: return false
        if (LAST_FM.any { under(h, it) }) return true
        return h in CAPTCHA_HOSTS && (path ?: "").startsWith("/recaptcha/")
    }

    /** Whether this host is an ad or tracking host. */
    fun isTracker(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return TRACKERS.any { under(h, it) }
    }

    /** The empty answer a refused request gets, with nothing asked of the network. */
    fun refused(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** For the Last.fm page's WebViewClient.shouldInterceptRequest: null loads the request as usual. */
    fun forLastFm(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (lastFmAllows(url.scheme, url.host, url.path)) return null
        Log.d(TAG, "Last.fm page: refused ${url.host}")
        return refused()
    }

    /** For the YouTube Music sign-in's WebViewClient.shouldInterceptRequest: null loads the request as usual. */
    fun forSignIn(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (!isTracker(url.host)) return null
        Log.d(TAG, "sign-in page: refused ${url.host}")
        return refused()
    }
}
