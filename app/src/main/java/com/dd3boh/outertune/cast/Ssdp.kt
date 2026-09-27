/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * SSDP, the search half of UPnP: one UDP datagram to the multicast group, answered by every
 * renderer on the network with the url of its description.
 *
 * Replies come back unicast to the socket that asked, so no multicast lock is needed to hear them.
 * UDP drops packets, so the search is sent three times, and replies are collected for a few
 * seconds and kept once per device.
 */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** One device's reply: where its description is, and who it says it is. */
    data class Reply(val location: String, val usn: String?, val searchTarget: String?, val server: String?) {
        /** What tells two replies from the same device apart from two devices. */
        val key: String get() = usn?.substringBefore("::")?.takeIf { it.isNotBlank() } ?: location
    }

    fun searchMessage(target: String = MEDIA_RENDERER, waitSeconds: Int = 2): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $MULTICAST_ADDRESS:$PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $waitSeconds\r\n")
        append("ST: $target\r\n")
        append("USER-AGENT: ${LanHttp.USER_AGENT}\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    /**
     * A reply to a search, or null for anything else. [from] is the address it came from.
     *
     * The description is fetched next, so where it points is checked: an address on the local
     * network, or the one the reply came from. A device that answers with a url on the internet,
     * or a name to look up, is not followed.
     */
    fun parseReply(text: String, from: InetAddress? = null): Reply? {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val statusLine = lines.firstOrNull()?.trim() ?: return null
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.", ignoreCase = true) || parts[1] != "200") return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val location = headers["location"]?.takeIf { it.isNotBlank() } ?: return null
        if (!isLocalUrl(location, from)) {
            Log.d(TAG, "Not following $location")
            return null
        }
        return Reply(location, headers["usn"], headers["st"], headers["server"])
    }

    /** Whether [url] is http to an address literal on the local network, or to [from] itself. */
    fun isLocalUrl(url: String, from: InetAddress? = null): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return false
        val address = literal(host) ?: return false
        if (from != null && address == from) return true
        return isLanAddress(address)
    }

    /** An IP literal as an address, without ever looking a name up; null for a name. */
    private fun literal(host: String): InetAddress? {
        val ipv4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        if (!ipv4.matches(host) && ':' !in host) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    /**
     * Private or link-local: the addresses a home network hands out. Not loopback: any device
     * answering a search could otherwise send the phone to its own loopback, to whatever other apps
     * serve there. A renderer on the phone itself answers from loopback, which [isLocalUrl] takes.
     */
    fun isLanAddress(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        return when (address) {
            // 100.64.0.0/10, which some routers and mesh systems hand out.
            is Inet4Address -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
            // fc00::/7, unique local addresses.
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /**
     * Searches for [timeoutMs] and returns each device that answered, once.
     *
     * [prepare] gets the socket before anything is sent: the app binds it to the Wi-Fi network
     * there, so a VPN does not swallow the search. [destination] is the multicast group, or in
     * tests a responder's own address.
     */
    fun search(
        target: String = MEDIA_RENDERER,
        timeoutMs: Long = 3_000,
        sends: Int = 3,
        destination: InetSocketAddress = InetSocketAddress(MULTICAST_ADDRESS, PORT),
        prepare: (DatagramSocket) -> Unit = {},
    ): List<Reply> {
        val found = LinkedHashMap<String, Reply>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            prepare(socket)
            if (!socket.isBound) socket.bind(InetSocketAddress(0))
            val message = searchMessage(target, waitSeconds = ((timeoutMs / 1000).toInt() - 1).coerceIn(1, 5))
            val start = System.nanoTime()
            val spacingMs = (timeoutMs / (sends * 3)).coerceIn(50, 500)
            var sent = 0
            val buffer = ByteArray(4096)
            while (true) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                if (elapsedMs >= timeoutMs) break
                if (sent < sends && elapsedMs >= sent * spacingMs) {
                    try {
                        socket.send(DatagramPacket(message, message.size, destination))
                    } catch (e: IOException) {
                        if (sent == 0) throw e
                        Log.d(TAG, "A search went unsent: ${e.message}")
                    }
                    sent++
                    continue
                }
                val nextSendMs = if (sent < sends) sent * spacingMs else timeoutMs
                socket.soTimeout = (minOf(nextSendMs, timeoutMs) - elapsedMs).coerceAtLeast(1).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                val reply = parseReply(text, packet.address) ?: continue
                found.putIfAbsent(reply.key, reply)
            }
        }
        return found.values.toList()
    }

    private const val TAG = "Ssdp"
}
