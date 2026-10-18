/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import com.dd3boh.outertune.cast.RecentReceivers.samePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** The receivers cast to before: kept in order, written and read back, merged with the search, and asked directly. */
class RecentReceiversTest {
    private val pixel = CastDevice(CastProtocol.FCAST, "FCast-Google-Pixel 5", "FCast-Google-Pixel 5", "192.168.1.187", 46899)
    private val tv = CastDevice(CastProtocol.CHROMECAST, "c0ffee", "Mel TV", "192.168.1.50", 8009, model = "Chromecast Ultra")
    private val group = CastDevice(CastProtocol.CHROMECAST, "beef", "Living room", "192.168.1.50", 32161, audioOnly = true)
    private val renderer = CastDevice(CastProtocol.DLNA, "uuid:1234", "Bedroom TV", "192.168.1.60", 49152, location = "http://192.168.1.60:49152/desc.xml")

    @Test
    fun `the latest first, each receiver once, five at most`() {
        var list = emptyList<CastDevice>()
        for (d in listOf(pixel, tv, group, renderer)) list = RecentReceivers.remember(list, d)
        assertEquals(listOf(renderer, group, tv, pixel), list)
        // Cast to again, under a new name: moved to the front, not listed twice.
        list = RecentReceivers.remember(list, tv.copy(name = "TV"))
        assertEquals(listOf("TV", "Bedroom TV", "Living room", "FCast-Google-Pixel 5"), list.map { it.name })
        for (i in 1..4) list = RecentReceivers.remember(list, pixel.copy(id = "p$i", host = "10.0.0.$i"))
        assertEquals(RecentReceivers.MOST, list.size)
        assertEquals("10.0.0.4", list.first().host)
    }

    @Test
    fun `a speaker group is not its speaker`() {
        assertFalse(tv.samePlace(group))
        assertTrue(tv.samePlace(tv.copy(id = "other", name = "renamed")))
        assertFalse(pixel.samePlace(pixel.copy(protocol = CastProtocol.DLNA)))
    }

    @Test
    fun `written and read back whole`() {
        val list = listOf(pixel, tv, group, renderer)
        assertEquals(list, RecentReceivers.decode(RecentReceivers.encode(list)))
    }

    @Test
    fun `what cannot be read is left out, never thrown`() {
        assertEquals(emptyList<CastDevice>(), RecentReceivers.decode(null))
        assertEquals(emptyList<CastDevice>(), RecentReceivers.decode(""))
        assertEquals(emptyList<CastDevice>(), RecentReceivers.decode("not json"))
        assertEquals(emptyList<CastDevice>(), RecentReceivers.decode("{}"))
        val one = RecentReceivers.encode(listOf(pixel))
        val withJunk = "[" + "{\"protocol\":\"AIRPLAY\",\"id\":\"x\",\"name\":\"x\",\"host\":\"x\",\"port\":1}," + one.removePrefix("[")
        assertEquals(listOf(pixel), RecentReceivers.decode(withJunk))
    }

    @Test
    fun `a receiver the search found is listed once, as found`() {
        val found = listOf(pixel.copy(name = "FCast Pixel, as it says now"))
        assertEquals(listOf(tv), RecentReceivers.notFound(listOf(pixel, tv), found))
        // A DLNA renderer found under its own id at a new address is still the same renderer.
        assertEquals(emptyList<CastDevice>(), RecentReceivers.notFound(listOf(renderer), listOf(renderer.copy(host = "192.168.1.61"))))
    }

    @Test
    fun `asked directly, at the address it had`() {
        ServerSocket(0).use { server ->
            val here = pixel.copy(host = "127.0.0.1", port = server.localPort)
            assertTrue(RecentReceivers.answers(here))
        }
        // Nothing listens there any more.
        val gone = ServerSocket(0).use { it.localPort }
        assertFalse(RecentReceivers.answers(pixel.copy(host = "127.0.0.1", port = gone), timeoutMs = 300))
    }
}
