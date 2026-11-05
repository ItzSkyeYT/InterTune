/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * The proxy setting covers everything the app fetches, covers and downloads to a folder included
 * (#68: blank albums behind a proxy), while the phone itself and the home network stay direct.
 */
class AppProxySelectorTest {
    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 1080))
    private val selector = AppProxySelector(proxy)

    private fun through(url: String) = selector.select(URI(url)).single()

    @Test
    fun `covers, downloads and the rest of the internet go through the proxy`() {
        for (url in listOf(
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://lh3.googleusercontent.com/abc=w544-h544",
            "https://rr1---sn-abc.googlevideo.com/videoplayback?id=1",
            "https://lrclib.net/api/get",
            "http://8.8.8.8/",
            "http://172.32.0.1/",
            "https://[2001:4860:4860::8888]/",
            "wss://relay.example.org/",
        )) assertEquals(url, proxy, through(url))
    }

    @Test
    fun `this phone and the home network stay direct, so a speaker on the Wi-Fi is still reached`() {
        for (url in listOf(
            "http://127.0.0.1:8080/song",
            "http://localhost:8080/",
            "http://10.0.0.5:1400/xml/device_description.xml",
            "http://172.16.0.5/",
            "http://172.31.255.254/",
            "http://192.168.1.7:49152/description.xml",
            "http://169.254.10.20/",
            "http://100.101.102.103/",
            "http://speaker.local:8008/",
            "http://[::1]:8080/",
            "http://[fe80::1]/",
            "http://[fd12:3456::1]/",
            "http://[::ffff:192.168.1.7]/",
        )) assertEquals(url, Proxy.NO_PROXY, through(url))
    }

    @Test
    fun `plain sockets are never sent to the proxy`() {
        assertEquals(Proxy.NO_PROXY, through("socket://8.8.8.8:443"))
    }

    @Test
    fun `a name is never looked up to decide`() {
        // A lookup would leave the phone outside the proxy before the proxy is asked; names that
        // are not .local go through, whatever they would resolve to.
        assertEquals(proxy, through("http://router.lan/"))
        assertFalse(AppProxySelector.isLocal("speaker.example.com"))
    }

    @Test
    fun `the app makes it the default when it starts with a proxy`() {
        val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
        val start = app.indexOf("if (proxy != null) {")
        assertTrue("the start no longer takes the proxy as it did", start >= 0)
        val block = app.substring(start, app.indexOf("} else Toaster.show", start))
        assertTrue("YouTube's own calls keep the proxy", "YouTube.proxy = proxy" in block)
        assertTrue("everything else takes it too", "ProxySelector.setDefault(AppProxySelector(proxy))" in block)
    }
}
