/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The proxy setting for everything the app fetches from the internet, made the default for the
 * whole app when it starts (App.onCreate). Only YouTube's own calls and the streams took it: covers,
 * downloads to a folder, lyrics, recognition and the rest went straight out, so a proxy set up to
 * reach YouTube Music where it is blocked still left the albums blank (#68), and one set up for
 * privacy let half the app round it. A client that names a proxy of its own keeps it.
 *
 * Addresses on this phone and on the home network go direct, as do names ending in .local: a
 * speaker on the Wi-Fi cannot be reached through a proxy somewhere else. So do plain sockets, which
 * are the casting links' own business (LanHttp, the Cast and FCast links).
 */
class AppProxySelector(private val proxy: Proxy) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.removeSurrounding("[", "]")
        return if (scheme in PROXIED && !host.isNullOrEmpty() && !isLocal(host)) listOf(proxy) else DIRECT
    }

    /** OkHttp tells the selector when the proxy could not be reached; it then fails the call, which is the point of a proxy. */
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    companion object {
        private val PROXIED = setOf("http", "https", "ws", "wss")
        private val DIRECT = listOf(Proxy.NO_PROXY)
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val IPV6 = Regex("""^[0-9A-Fa-f:.]+(%.+)?$""")

        /**
         * Whether [host] is this phone or the home network: localhost and names ending in .local, and
         * the loopback, private, link-local and carrier-grade (Tailscale's) ranges, in IPv4 and IPv6.
         * Read from the address as written: a name is never looked up here, which would send it
         * outside the proxy before the proxy is even asked.
         */
        fun isLocal(host: String): Boolean {
            val name = host.lowercase().trimEnd('.')
            if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
            val literal = name.contains(':') && IPV6.matches(name) || IPV4.matches(name)
            if (!literal) return false
            val address = runCatching { InetAddress.getByName(name.substringBefore('%')) }.getOrNull() ?: return false
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return true
            val b = address.address
            return when (b.size) {
                // 100.64.0.0/10, shared by carriers and by Tailscale's addresses.
                4 -> b[0].toInt() and 0xff == 100 && b[1].toInt() and 0xc0 == 64
                // fc00::/7, the unique local addresses a home router hands out.
                16 -> b[0].toInt() and 0xfe == 0xfc
                else -> false
            }
        }
    }
}
