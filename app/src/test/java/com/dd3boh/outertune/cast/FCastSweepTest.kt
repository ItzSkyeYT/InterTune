/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** FCast receivers looked for address by address: which addresses, and what a receiver says. */
class FCastSweepTest {
    private fun ip(text: String) = InetAddress.getByName(text) as Inet4Address

    @Test
    fun `the other addresses of a home network, and none of a large one`() {
        val home = FCastSweep.hosts(ip("192.168.1.47"), 24).map { it.hostAddress }
        assertEquals(253, home.size)
        assertEquals("192.168.1.1", home.first())
        assertEquals("192.168.1.254", home.last())
        assertTrue("192.168.1.47" !in home && "192.168.1.0" !in home && "192.168.1.255" !in home)
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.5", "10.0.0.6"), FCastSweep.hosts(ip("10.0.0.4"), 29).map { it.hostAddress })
        assertEquals(emptyList<Inet4Address>(), FCastSweep.hosts(ip("10.1.2.3"), 16))
        assertEquals(emptyList<Inet4Address>(), FCastSweep.hosts(ip("10.1.2.3"), 31))
    }

    /** A receiver on this machine that answers Version as FCast Receiver does: Initial, then Version. */
    private fun receiver(name: String?, answer: Boolean = true): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { client ->
                    if (!answer) {
                        Thread.sleep(2_000)
                        return@use
                    }
                    val frame = FCast.read(client.getInputStream())
                    if (frame?.opcode == FCast.VERSION_MESSAGE) {
                        val out = client.getOutputStream()
                        if (name != null) out.write(FCast.encode(FCast.INITIAL, FCast.initialBody(name, "FCast Receiver Android", "2.1.5")))
                        out.write(FCast.encode(FCast.VERSION_MESSAGE, FCast.versionBody()))
                        out.flush()
                    }
                    Thread.sleep(200)
                }
            }
        }
        return server
    }

    @Test
    fun `a receiver is named as it names itself`() {
        receiver("FCast-Google-Pixel 5").use { server ->
            val device = FCastSweep.probe(InetAddress.getLoopbackAddress(), server.localPort)!!
            assertEquals(CastProtocol.FCAST, device.protocol)
            assertEquals("FCast-Google-Pixel 5", device.name)
            assertEquals("FCast-Google-Pixel 5", device.id)
            assertEquals("127.0.0.1", device.host)
            assertEquals(server.localPort, device.port)
        }
    }

    @Test
    fun `a receiver that says no name is named by its address`() {
        receiver(null).use { server ->
            assertEquals("127.0.0.1", FCastSweep.probe(InetAddress.getLoopbackAddress(), server.localPort)!!.name)
        }
        receiver("ignored", answer = false).use { server ->
            assertEquals("127.0.0.1", FCastSweep.probe(InetAddress.getLoopbackAddress(), server.localPort)!!.name)
        }
    }

    @Test
    fun `nothing there, nothing listed`() {
        val gone = ServerSocket(0).use { it.localPort }
        assertNull(FCastSweep.probe(InetAddress.getLoopbackAddress(), gone))
    }

    /**
     * Sockets as a network the app may not use gives them, as on the Xiaomi of issue #33: binding
     * one to it fails with EPERM before anything is sent. [refuses] picks which, in the order they
     * are asked for.
     */
    private class Forbidding(private val refuses: (Int) -> Boolean = { true }) : SocketFactory() {
        val asked = AtomicInteger()

        override fun createSocket(): Socket {
            if (refuses(asked.getAndIncrement())) throw SocketException("Binding socket to network 1040 failed: EPERM (Operation not permitted)")
            return Socket()
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    }

    @Test
    fun `a network that refuses every socket has no receivers, and the sweep asks every address without failing`() {
        val forbidden = Forbidding()
        val hosts = FCastSweep.hosts(ip("192.168.1.47"), 24)
        val found = CopyOnWriteArrayList<CastDevice>()
        // The refusal came out of the sweep, then out of the picker's coroutine, and closed the app.
        runBlocking { FCastSweep.sweep(hosts, forbidden) { found += it } }
        assertEquals(emptyList<CastDevice>(), found)
        assertEquals(hosts.size, forbidden.asked.get())
    }

    @Test
    fun `a socket refused for one address does not keep the sweep from a receiver at the next`() {
        receiver("FCast-Google-Pixel 5").use { server ->
            // Under runBlocking the probes run one after the other, so the refused socket is the first address's.
            val forbidden = Forbidding { it == 0 }
            val found = CopyOnWriteArrayList<CastDevice>()
            runBlocking {
                FCastSweep.sweep(listOf(ip("127.0.0.2"), InetAddress.getLoopbackAddress()), forbidden, server.localPort) { found += it }
            }
            assertEquals(listOf("FCast-Google-Pixel 5"), found.map { it.name })
            assertEquals(2, forbidden.asked.get())
        }
    }
}
