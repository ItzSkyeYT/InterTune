/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Receivers typed in by address, those NSD finds, and the list the searches make. */
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
    fun `chromecasts typed in by address`() {
        val plain = CastAddress.parse("chromecast 192.168.1.21")!!
        assertEquals(CastProtocol.CHROMECAST, plain.protocol)
        assertEquals("192.168.1.21", plain.host)
        assertEquals(8009, plain.port)
        assertEquals("192.168.1.21", plain.name)
        assertEquals("chromecast:192.168.1.21:8009", plain.id)
        // A speaker group listens on a port of its own.
        assertEquals(32187, CastAddress.parse("Chromecast 192.168.1.21:32187")!!.port)
        assertEquals(32187, CastAddress.parse("chromecast://192.168.1.21:32187/")!!.port)
        assertEquals(CastProtocol.CHROMECAST, CastAddress.parse("chromecast://tv.local")!!.protocol)
        val v6 = CastAddress.parse("chromecast [fd00::7]")!!
        assertEquals("fd00::7", v6.host)
        assertEquals(8009, v6.port)
        // An address alone on the Cast port can only be a Chromecast; FCast never listens there.
        assertEquals(CastProtocol.CHROMECAST, CastAddress.parse("10.0.2.2:8009")!!.protocol)
        assertEquals(CastProtocol.FCAST, CastAddress.parse("fcast 10.0.2.2:8009")!!.protocol)
        assertEquals(CastProtocol.FCAST, CastAddress.parse("10.0.2.2")!!.protocol)
    }

    @Test
    fun `what cannot be a receiver's address is refused`() {
        assertNull(CastAddress.parse("chromecast"))
        assertNull(CastAddress.parse("chromecast http://10.0.2.2/desc.xml"))
        assertNull(CastAddress.parse("chromecast 10.0.2.2:0"))
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

    @Test
    fun `a Chromecast found by NSD goes by the name people gave it, and its own id`() {
        val txt = mapOf(
            "id" to "5d1f0c7e8b2a4c1d".toByteArray(),
            "fn" to "Living room TV".toByteArray(),
            "md" to "Chromecast".toByteArray(),
            "ca" to "4101".toByteArray(),
            "rs" to null,
        )
        val tv = CastDevices.chromecast("Chromecast-5d1f0c7e8b2a4c1d", InetAddress.getByName("192.168.1.40"), 8009, txt)
        assertEquals(CastDevice(CastProtocol.CHROMECAST, "chromecast:5d1f0c7e8b2a4c1d", "Living room TV", "192.168.1.40", 8009, model = "Chromecast"), tv)
        assertFalse(tv.audioOnly)

        // A group: no screen (ca 2084 lacks the video bit), a port of its own, and the leader's address.
        val group = CastDevices.chromecast(
            "Google-Cast-Group-8a1b", InetAddress.getByName("192.168.1.40"), 32187,
            mapOf("id" to "8a1b".toByteArray(), "fn" to "Downstairs".toByteArray(), "md" to "Google Cast Group".toByteArray(), "ca" to "2084".toByteArray()),
        )
        assertTrue(group.audioOnly)
        assertEquals(32187, group.port)

        // Without fn the model names it, and without either the service name does.
        assertEquals("Google Nest Mini", CastDevices.chromecast("Google-Nest-Mini-1", InetAddress.getByName("192.168.1.41"), 8009, mapOf("md" to "Google Nest Mini".toByteArray())).name)
        val bare = CastDevices.chromecast("Chromecast-77", InetAddress.getByName("192.168.1.42"), 8009, emptyMap())
        assertEquals("Chromecast-77", bare.name)
        assertEquals("chromecast:Chromecast-77", bare.id)
        assertFalse(bare.audioOnly)
    }

    @Test
    fun `a TV with Chromecast is shown once as that, not as DLNA, and a group beside its leader both show`() {
        val tv = CastDevice(CastProtocol.CHROMECAST, "chromecast:a", "Bedroom TV", "192.168.1.40", 8009)
        val group = CastDevice(CastProtocol.CHROMECAST, "chromecast:g", "Downstairs", "192.168.1.40", 32187, audioOnly = true)
        val dlnaTv = CastDevice(CastProtocol.DLNA, "uuid:tv", "Bedroom TV (DLNA)", "192.168.1.40", 9197, "http://192.168.1.40:9197/d.xml")
        val fcastTv = CastDevice(CastProtocol.FCAST, "fcast:TV", "Bedroom TV", "192.168.1.40", 46899)
        assertEquals(listOf(tv, group), CastDevices.merge(emptyList(), listOf(dlnaTv), listOf(tv, group)))
        assertEquals(listOf(fcastTv, tv, group), CastDevices.merge(listOf(fcastTv), listOf(dlnaTv), listOf(tv, group, tv)))
    }

    @Test
    fun `nothing the picker's searches throw can reach the app`() {
        // CastDiscovery needs the system's NsdManager, so this reads the source: every coroutine it
        // launches runs inside safely(). A sweep that let a refused socket out closed the app (issue #33).
        val discovery = java.io.File("src/main/java/com/dd3boh/outertune/cast/CastDiscovery.kt").readText()
        val launched = discovery.split("scope.launch(").drop(1).map { it.substringAfter('{').trimStart() }
        assertEquals(2, launched.size)
        assertTrue(launched.all { it.startsWith("safely(") })
    }

    @Test
    fun `a renderer that never sends its description is given up within the search's limits`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        // Takes the connection and says nothing, for ever.
        val silent = ServerSocket(0, 4, loopback)
        val held = CopyOnWriteArrayList<Socket>()
        thread(isDaemon = true) { runCatching { while (true) held += silent.accept() } }
        val responder = DatagramSocket(0, loopback)
        thread(isDaemon = true) {
            val buffer = ByteArray(2048)
            runCatching {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    responder.receive(packet)
                    val reply = ("HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:${silent.localPort}/desc.xml\r\n" +
                        "ST: ${Ssdp.MEDIA_RENDERER}\r\nUSN: uuid:silent::${Ssdp.MEDIA_RENDERER}\r\n\r\n").toByteArray()
                    responder.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                }
            }
        }
        try {
            val started = System.nanoTime()
            val found = runBlocking {
                DlnaDiscovery.search(timeoutMs = 500, http = DlnaDiscovery.descriptionHttp(), destination = InetSocketAddress(loopback, responder.localPort))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(found.isEmpty())
            assertTrue("asked, then waited on its description", held.isNotEmpty())
            // Half a second of search and four of waiting for the answer; it used to be eight.
            assertTrue("gave up after $elapsedMs ms", elapsedMs in 4_000..6_500)
        } finally {
            responder.close()
            silent.close()
            held.forEach { runCatching { it.close() } }
        }
    }
}
