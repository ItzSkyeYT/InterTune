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
 * The proxy switched on with no address typed, or with an address as people write them
 * ("http://127.0.0.1:8118", one with a slash at the end, an IPv6 one in brackets), failed at the
 * next start with an English toast, "Failed to parse proxy url.", and the app ran without the
 * proxy (audit of 2 Nov 2026, 3.11). The field took anything, and showed "host:port" when nothing
 * was stored, as if that were the address.
 */
class ProxyAddressTest {
    private val src = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the forms people type are taken`() {
        assertEquals("127.0.0.1:8118", ProxyAddress.normalized("127.0.0.1:8118"))
        assertEquals("127.0.0.1:8118", ProxyAddress.normalized(" http://127.0.0.1:8118 "))
        assertEquals("127.0.0.1:9050", ProxyAddress.normalized("socks5://127.0.0.1:9050/"))
        assertEquals("proxy.example.org:3128", ProxyAddress.normalized("HTTPS://proxy.example.org:3128/path"))
        assertEquals("localhost:8080", ProxyAddress.normalized("localhost:8080"))
        assertEquals("[::1]:8118", ProxyAddress.normalized("[::1]:8118"))
        assertEquals("[2001:db8::2]:1080", ProxyAddress.normalized("socks://[2001:db8::2]:1080"))
        val address = ProxyAddress.parse("http://10.0.0.2:8118")!!
        assertEquals("10.0.0.2", address.hostString)
        assertEquals(8118, address.port)
    }

    @Test
    fun `what cannot be used is refused, an empty field first`() {
        for (text in listOf(
            null, "", "   ", "host:port", "127.0.0.1", "127.0.0.1:", ":8118", "http://", "127.0.0.1:0",
            "127.0.0.1:65536", "127.0.0.1:99999999", "127.0.0.1:81 18", "::1:8118", "[::1]8118", "[::1",
            // A user and a password could not be passed on.
            "user:secret@127.0.0.1:8118",
        )) {
            assertNull(text, ProxyAddress.parse(text))
        }
    }

    @Test
    fun `the start reads the address as the settings page does, and says it in the listener's language`() {
        val app = File(src, "App.kt").readText()
        assertFalse(app.contains("Failed to parse proxy url."))
        assertTrue(app.contains("ProxyAddress.parse(dataStore[ProxyUrlKey])"))
        assertTrue(app.contains("R.string.proxy_not_used_at_start"))
    }

    @Test
    fun `the setting refuses an address it cannot use, and says so in plain words`() {
        val page = File(src, "ui/screens/settings/PrivacySettings.kt").readText()
        assertTrue(page.contains("isInputValid = { ProxyAddress.parse(it) != null }"))
        assertTrue(page.contains("R.string.proxy_address_format"))
        assertTrue(page.contains("R.string.proxy_address_missing"))
        // An address that was never typed is not shown as if it were one.
        assertFalse(page.contains("defaultValue = \"host:port\""))
    }
}
