/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.annotation.SuppressLint
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS to a Chromecast, from the platform's own TLS (the JDK's on a computer, Conscrypt on Android),
 * with nothing added to the build.
 *
 * A Cast device has no certificate a phone could check: it makes its own, self-signed, and proves
 * who it is only through the device authentication namespace, against Google's Cast root, which is
 * Google's business rather than a music player's. Chrome's own sender, pychromecast, node-castv2
 * and FUTO's sender SDK all take the device's certificate as it is, and so does this, under two
 * limits: only for the one socket made here, never for the app's other connections, and only to
 * an address on the local network ([isLocal]), which is where a Chromecast is and where the
 * connection was found or typed in.
 */
object ChromecastTls {
    /**
     * Puts TLS over [socket], already connected to [host] at [port], and shakes hands within the
     * socket's read timeout. Throws when the far end does not speak TLS.
     */
    fun wrap(socket: Socket, host: String, port: Int): Socket {
        val tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        tls.startHandshake()
        return tls
    }

    /**
     * Whether [address] is on the local network: a private IPv4 range, a unique local IPv6 address,
     * a link-local one, or loopback, which is where the tests' fake receiver listens.
     */
    fun isLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)

    private val context: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(AnyCertificate), null) }
    }

    /** Takes whatever certificate the device shows; see the class comment for why, and where. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
