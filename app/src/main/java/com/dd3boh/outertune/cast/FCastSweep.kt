/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * FCast receivers looked for directly, address by address, on a home-sized Wi-Fi network.
 *
 * The network's search (mDNS) finds a receiver only if it hears the question, and FCast Receiver
 * 2.1.5 on Android does not: it asks for a multicast lock without the permission for one
 * (CHANGE_WIFI_MULTICAST_STATE), so its phone's Wi-Fi drops every question and it can be found
 * only in the seconds after it starts, while it announces itself (seen 18 Oct 2026 on a Pixel 5).
 * So while the picker is open, each address of the phone's own network of at most 256 is offered
 * a connection on FCast's port; one that takes it is asked its name the way the link asks it
 * (Version, then the receiver's Initial), and the connection closed. Nothing shows on the
 * receiver's screen for it. A network larger than that is not swept.
 */
object FCastSweep {
    const val CONNECT_MS = 300
    const val NAME_MS = 1_200
    const val PARALLEL = 48

    /**
     * The other addresses of [self]'s network: not the network's own address, not its broadcast
     * address, not [self]. Empty for a network of more than 256 addresses (a prefix under 24).
     */
    fun hosts(self: Inet4Address, prefix: Int): List<Inet4Address> {
        if (prefix !in 24..30) return emptyList()
        val ip = ByteBuffer.wrap(self.address).int
        val size = 1 shl (32 - prefix)
        val base = ip and (-1 shl (32 - prefix))
        return (1 until size - 1)
            .map { base + it }
            .filter { it != ip }
            .map { InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(it).array()) as Inet4Address }
    }

    /**
     * The FCast receiver at [host], named as it names itself, or by its address when it says no
     * name; null when nothing takes a connection there. A socket the network will not give at all
     * comes out as its IOException, which [sweep] takes as nothing there. It blocks: never on the
     * main thread.
     */
    fun probe(host: InetAddress, port: Int = FCast.PORT, socketFactory: SocketFactory = SocketFactory.getDefault()): CastDevice? {
        val socket = socketFactory.createSocket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
        } catch (_: IOException) {
            runCatching { socket.close() }
            return null
        }
        val name = socket.use {
            runCatching {
                it.soTimeout = NAME_MS
                it.getOutputStream().apply {
                    write(FCast.encode(FCast.VERSION_MESSAGE, FCast.versionBody()))
                    flush()
                }
                val input = it.getInputStream()
                var said: String? = null
                // A v3 receiver answers with its Version and its Initial, in either order.
                for (i in 0 until 3) {
                    val frame = FCast.read(input) ?: break
                    if (frame.opcode == FCast.INITIAL) {
                        said = FCast.parseInitial(frame.body)?.displayName?.trim()?.takeIf { n -> n.isNotEmpty() }
                        break
                    }
                }
                said
            }.getOrNull()
        }
        val address = host.hostAddress ?: return null
        return CastDevice(CastProtocol.FCAST, id = name ?: "fcast@$address", name = name ?: address, host = address, port = port)
    }

    /**
     * Each of [hosts] probed, [PARALLEL] at a time, and each receiver handed to [found] as soon as
     * it answers. It returns once every address has been asked.
     *
     * An address the network will not give a socket for has no receiver, and the sweep goes on
     * with the others. A network the app may not use refuses every socket bound to it with EPERM,
     * before any connection is tried: Xiaomi's per-app firewall does that, and so can a VPN that
     * blocks other ways out. Let out of here, the refusal closed the app (issue #33). It is said
     * once in the log, however many addresses it refuses.
     */
    suspend fun sweep(hosts: List<InetAddress>, socketFactory: SocketFactory, port: Int = FCast.PORT, found: (CastDevice) -> Unit) {
        val gate = Semaphore(PARALLEL)
        val refused = AtomicBoolean(false)
        coroutineScope {
            for (host in hosts) launch {
                val device = try {
                    gate.withPermit { probe(host, port, socketFactory) }
                } catch (e: IOException) {
                    if (refused.compareAndSet(false, true)) {
                        Log.w(TAG, "Skipping addresses the network refuses a socket for: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    null
                } ?: return@launch
                found(device)
            }
        }
    }

    private const val TAG = "FCastSweep"
}
