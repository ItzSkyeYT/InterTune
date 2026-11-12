/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.net.InetSocketAddress

/**
 * The proxy's address as people type it. It was split at its colons and nothing more, so an
 * address written the way proxy apps and pages give it, "http://127.0.0.1:8118", failed at the next
 * start with an English toast and the app ran without the proxy, and so did the switch turned on
 * with nothing typed (audit of 2 Nov 2026). The settings page and the start read it the same way.
 */
object ProxyAddress {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * The address in [text], or null when it cannot be used: host:port with a port from 1 to 65535,
     * after a scheme ("http://", "socks5://") and before a slash, which are left out, and an IPv6
     * host in brackets. One with a user and a password is refused, since they could not be passed
     * on, and so is one with no port, since no port is right for every proxy.
     */
    fun parse(text: String?): InetSocketAddress? {
        var rest = text?.trim().orEmpty()
        SCHEME.find(rest)?.let { rest = rest.substring(it.range.last + 1) }
        rest = rest.substringBefore('/')
        if (rest.isEmpty() || '@' in rest || rest.any { it.isWhitespace() }) return null
        val host: String
        val port: String
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            if (!after.startsWith(":")) return null
            port = after.substring(1)
        } else {
            host = rest.substringBeforeLast(':', "")
            port = rest.substringAfterLast(':', "")
            // An IPv6 address with no brackets cannot be told from its port.
            if (':' in host) return null
        }
        if (host.isEmpty() || '[' in host || ']' in host) return null
        if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return null
        val number = port.toInt()
        if (number !in 1..65535) return null
        return InetSocketAddress.createUnresolved(host, number)
    }

    /** The address in [text] written one way, host:port, to compare two settings by, or null. */
    fun normalized(text: String?): String? = parse(text)?.let { address ->
        val host = address.hostString
        if (':' in host) "[$host]:${address.port}" else "$host:${address.port}"
    }
}
