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
    fun `the latest first, each receiver once, ten at most`() {
        var list = emptyList<CastDevice>()
        for (d in listOf(pixel, tv, group, renderer)) list = RecentReceivers.remember(list, d)
        assertEquals(listOf(renderer, group, tv, pixel), list)
        // Cast to again, under a new name: moved to the front, not listed twice.
        list = RecentReceivers.remember(list, tv.copy(name = "TV"))
        assertEquals(listOf("TV", "Bedroom TV", "Living room", "FCast-Google-Pixel 5"), list.map { it.name })
        for (i in 1..RecentReceivers.MOST) list = RecentReceivers.remember(list, pixel.copy(id = "p$i", host = "10.0.0.$i"))
        assertEquals(RecentReceivers.MOST, list.size)
        assertEquals("10.0.0.${RecentReceivers.MOST}", list.first().host)
    }

    @Test
    fun `receivers the search finds are kept, each once, without reordering those known`() {
        var list = listOf(tv, pixel)
        // Found again under a new name: updated where it stands. A new one: first.
        list = RecentReceivers.seen(list, listOf(pixel.copy(name = "Pixel, renamed"), renderer))
        assertEquals(listOf("Bedroom TV", "Mel TV", "Pixel, renamed"), list.map { it.name })
        // The same renderer at a new address keeps its id: still one entry.
        list = RecentReceivers.seen(list, listOf(renderer.copy(host = "192.168.1.61")))
        assertEquals(3, list.size)
        assertEquals("192.168.1.61", list.first().host)
        val many = (1..20).map { pixel.copy(id = "p$it", host = "10.0.1.$it") }
        assertEquals(RecentReceivers.MOST, RecentReceivers.seen(list, many).size)
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

    @Test
    fun `a remembered receiver is listed only once it has answered, and a found one goes when it stops answering`() {
        val recent = listOf(tv, pixel, renderer)
        // Nothing has answered yet: none of the remembered ones shows, not even for a moment (20 Oct 2026).
        assertEquals(emptyList<CastDevice>(), RecentReceivers.listed(emptyList(), emptyList(), recent, emptySet(), emptySet()))
        // The TV answers; the others are off and never show.
        assertEquals(listOf(tv), RecentReceivers.listed(emptyList(), emptyList(), recent, setOf(tv), setOf(pixel, renderer)))
        // The search finds the renderer: listed with the rest, by name.
        assertEquals(listOf("Bedroom TV", "Mel TV"), RecentReceivers.listed(listOf(renderer), emptyList(), recent, setOf(tv), emptySet()).map { it.name })
        // Switched off while the picker is open: the next check takes it off.
        assertEquals(listOf("Mel TV"), RecentReceivers.listed(listOf(renderer), emptyList(), recent, setOf(tv), setOf(renderer)).map { it.name })
        // One typed in by address stays, whatever the checks say: it was asked for.
        val typed = pixel.copy(id = "typed", name = "Typed", host = "10.0.0.9")
        assertTrue(typed in RecentReceivers.listed(emptyList(), listOf(typed), recent, emptySet(), setOf(typed)))
    }

    @Test
    fun `the picker lists what answers and asks again while it is open`() {
        val picker = java.io.File("src/main/java/com/dd3boh/outertune/ui/player/CastPicker.kt").readText()
        assertTrue(picker.contains("RecentReceivers.listed(found, added, recent, answered, silent)"))
        assertTrue(picker.contains("delay(RecentReceivers.RECHECK_MS)"))
    }
}
