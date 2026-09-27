/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Where the cast server listens: the phone's address on Wi-Fi, and nowhere else.
 *
 * Receivers are on the local network, so that is the only place the server needs to be reachable
 * from. Bound to that one address, it is not reachable over mobile data, nor from the other side
 * of a VPN, nor, when the phone is a hotspot, from the devices using it.
 */
object CastNetwork {
    /** The Wi-Fi network and the phone's address on it. */
    data class Wifi(val network: Network, val address: InetAddress)

    /** The phone's Wi-Fi network and address, or null when it is not on Wi-Fi. */
    fun wifi(connectivityManager: ConnectivityManager): Wifi? {
        // allNetworks, not activeNetwork: with a VPN up the active network is the VPN, and the
        // receiver is still on the Wi-Fi underneath it.
        @Suppress("DEPRECATION")
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) continue
            val addresses = connectivityManager.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?: continue
            val address = pickAddress(addresses) ?: continue
            return Wifi(network, address)
        }
        return null
    }

    /**
     * The address to listen on and hand to receivers: IPv4 first, since that is what every
     * receiver can reach, then a routable IPv6 one. Never loopback, link-local or multicast: a
     * link-local IPv6 address needs a zone no receiver would be given.
     */
    fun pickAddress(addresses: List<InetAddress>): InetAddress? = addresses
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
        .sortedBy { if (it is Inet4Address) 0 else 1 }
        .firstOrNull()

    /**
     * Calls [onGone] when [wifi] goes away or the phone loses its address on it, and returns the
     * callback for unregistering. A server left listening on an address that is gone would be
     * serving nobody, and one on an address now given to another network would be serving the
     * wrong people.
     */
    fun watch(connectivityManager: ConnectivityManager, wifi: Wifi, onGone: () -> Unit): ConnectivityManager.NetworkCallback {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == wifi.network) onGone()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == wifi.network && linkProperties.linkAddresses.none { it.address == wifi.address }) {
                    onGone()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        return callback
    }
}
