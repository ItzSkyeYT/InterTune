/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Receivers typed in by address, and the list both searches make. */
class CastDiscoveryTest {
    @Test
    fun `fcast receivers typed in by address`() {
        val plain = CastAddress.parse("10.0.2.2")!!
        assertEquals(CastProtocol.FCAST, plain.protocol)
        assertEquals("10.0.2.2", plain.host)
        assertEquals(46899, plain.port)
        assertEquals(12345, CastAddress.parse("fcast 10.0.2.2:12345")!!.port)
        assertEquals(12345, CastAddress.parse("FCAST  10.0.2.2:12345 ")!!.port)
        assertEquals(46899, CastAddress.parse("fcast://tv.local/")!!.port)
        assertEquals("tv.local", CastAddress.parse("fcast://tv.local/")!!.host)
        val v6 = CastAddress.parse("[fe80::1%wlan0]:4000")!!
        assertEquals("fe80::1%wlan0", v6.host)
        assertEquals(4000, v6.port)
        assertEquals(46899, CastAddress.parse("fd00::5")!!.port)
    }

    @Test
    fun `dlna renderers typed in by their description's url`() {
        val device = CastAddress.parse("dlna http://10.0.2.2:49152/desc.xml")!!
        assertEquals(CastProtocol.DLNA, device.protocol)
        assertEquals("http://10.0.2.2:49152/desc.xml", device.location)
        assertEquals("10.0.2.2", device.host)
        assertEquals(49152, device.port)
        assertEquals(80, CastAddress.parse("http://192.168.1.9/rootDesc.xml")!!.port)
    }

    @Test
    fun `what cannot be a receiver's address is refused`() {
        assertNull(CastAddress.parse(""))
        assertNull(CastAddress.parse("   "))
        assertNull(CastAddress.parse("dlna 10.0.2.2"))
        assertNull(CastAddress.parse("10.0.2.2:notaport"))
        assertNull(CastAddress.parse("10.0.2.2:70000"))
        assertNull(CastAddress.parse("a b c"))
        assertNull(CastAddress.parse("fcast"))
    }

    @Test
    fun `a TV found both ways is shown once, as FCast, and the list is sorted by name`() {
        val fcastTv = CastDevice(CastProtocol.FCAST, "fcast:TV", "Living room TV", "192.168.1.30", 46899)
        val dlnaTv = CastDevice(CastProtocol.DLNA, "uuid:tv", "Living room TV (DLNA)", "192.168.1.30", 9197, "http://192.168.1.30:9197/d.xml")
        val speaker = CastDevice(CastProtocol.DLNA, "uuid:spk", "bedroom speaker", "192.168.1.31", 1400, "http://192.168.1.31:1400/d.xml")
        val merged = CastDevices.merge(listOf(fcastTv), listOf(dlnaTv, speaker, speaker))
        assertEquals(listOf(speaker, fcastTv), merged)
    }
}
