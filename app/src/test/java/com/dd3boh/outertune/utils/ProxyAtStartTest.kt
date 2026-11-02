/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The proxy is taken once, when the app starts, so the settings page offers a restart while the
 * proxy set there is not the one in use (#68: Orbot's address typed in looked like it did nothing).
 */
class ProxyAtStartTest {
    @Test
    fun `a proxy that is off is one setting, whatever its fields still hold`() {
        assertEquals("off", ProxyAtStart.of(false, "SOCKS", "127.0.0.1:9050"))
        assertEquals("off", ProxyAtStart.of(null, null, null))
    }

    @Test
    fun `a proxy that is on is its type and its address`() {
        assertEquals("SOCKS 127.0.0.1:9050", ProxyAtStart.of(true, "SOCKS", " 127.0.0.1:9050 "))
        assertEquals("the page's default type", "HTTP 127.0.0.1:8118", ProxyAtStart.of(true, null, "127.0.0.1:8118"))
        assertNotEquals(ProxyAtStart.of(true, "HTTP", "10.0.0.2:1080"), ProxyAtStart.of(true, "SOCKS", "10.0.0.2:1080"))
        // The same address, however it was typed, is the same proxy: no restart for a scheme typed in.
        assertEquals(ProxyAtStart.of(true, "HTTP", "127.0.0.1:8118"), ProxyAtStart.of(true, "HTTP", "http://127.0.0.1:8118/"))
    }

    @Test
    fun `on with no address the start can use is no proxy, so no restart is offered for it`() {
        assertEquals("off", ProxyAtStart.of(true, "HTTP", null))
        assertEquals("off", ProxyAtStart.of(true, "HTTP", ""))
        assertEquals("off", ProxyAtStart.of(true, "SOCKS", "127.0.0.1"))
    }

    @Test
    fun `the app notes the proxy it starts with, the page offers the restart, the note leads to the switch`() {
        val src = File("src/main/java/com/dd3boh/outertune")
        val app = File(src, "App.kt").readText()
        val noted = app.indexOf("ProxyAtStart.setting = ProxyAtStart.of(")
        assertTrue("noted before the proxy is taken", noted > 0 && noted < app.indexOf("if (dataStore[ProxyEnabledKey] == true)"))
        assertTrue(File(src, "ui/screens/settings/PrivacySettings.kt").readText().contains("ProxyAtStart.restart(context)"))
        assertTrue(File(src, "ui/component/RegionNote.kt").readText().contains("R.string.enable_proxy }.jump()"))
    }
}
