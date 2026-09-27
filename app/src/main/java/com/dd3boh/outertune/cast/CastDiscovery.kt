/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.constants.Unreleased
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

/**
 * Receivers typed in by address, for the emulator, whose NAT neither mDNS nor SSDP crosses, and
 * for networks that block multicast (design.md section 8).
 *
 * - `fcast 10.0.2.2:46899`, `fcast://10.0.2.2`, or an address alone: an FCast receiver, on 46899
 *   unless a port is given;
 * - `dlna http://10.0.2.2:49152/desc.xml`, or any http url: a DLNA renderer's description.
 */
object CastAddress {
    fun parse(text: String): CastDevice? {
        var value = text.trim()
        var protocol: CastProtocol? = null
        // The word alone is the start of an address, not a host called "fcast".
        if (value.equals("fcast", ignoreCase = true) || value.equals("dlna", ignoreCase = true)) return null
        val word = value.substringBefore(' ').lowercase()
        if (' ' in value && (word == "fcast" || word == "dlna")) {
            protocol = if (word == "fcast") CastProtocol.FCAST else CastProtocol.DLNA
            value = value.substringAfter(' ').trim()
        }
        if (value.startsWith("fcast://", ignoreCase = true)) {
            protocol = CastProtocol.FCAST
            value = value.substring("fcast://".length).trimEnd('/')
        }
        if (value.isEmpty()) return null
        if (value.startsWith("http://", ignoreCase = true) && protocol != CastProtocol.FCAST) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port > 0) uri.port else 80
            return CastDevice(CastProtocol.DLNA, "location:$value", host, host, port, location = value)
        }
        if (protocol == CastProtocol.DLNA) return null
        val (host, port) = hostAndPort(value) ?: return null
        return CastDevice(CastProtocol.FCAST, "fcast:$host:$port", host, host, port)
    }

    /** `host`, `host:port`, `[v6]` or `[v6]:port`; a bare IPv6 address has no port. */
    private fun hostAndPort(value: String): Pair<String, Int>? {
        if (value.startsWith("[")) {
            val close = value.indexOf(']').takeIf { it > 1 } ?: return null
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            val port = if (rest.isEmpty()) FCast.PORT else rest.removePrefix(":").toIntOrNull() ?: return null
            return (host to port).takeIf { port in 1..65535 }
        }
        if (value.count { it == ':' } > 1) return value to FCast.PORT
        val host = value.substringBefore(':')
        val port = if (':' in value) value.substringAfter(':').toIntOrNull() ?: return null else FCast.PORT
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) return null
        return (host to port).takeIf { port in 1..65535 }
    }
}

/** How the receivers found by both searches are shown: once each, by name. */
object CastDevices {
    /**
     * [fcast] and [dlna] as one list. A TV running an FCast receiver often also answers as a DLNA
     * renderer; it is shown once, as FCast, which plays our songs as they are and reports far
     * better.
     */
    fun merge(fcast: Collection<CastDevice>, dlna: Collection<CastDevice>): List<CastDevice> {
        val fcastHosts = fcast.map { it.host.lowercase() }.toSet()
        return (fcast + dlna.filter { it.host.lowercase() !in fcastHosts })
            .distinctBy { it.protocol to it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** An FCast receiver as NSD found it. Its TXT record says little that matters to a sender. */
    fun fcast(serviceName: String, host: InetAddress, port: Int, attributes: Map<String, ByteArray?>): CastDevice {
        val appName = attributes["appName"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        return CastDevice(
            protocol = CastProtocol.FCAST,
            id = "fcast:$serviceName",
            name = serviceName,
            host = host.hostAddress ?: host.toString(),
            port = port,
            model = appName?.let { "FCast $it" },
        )
    }
}

/**
 * Looks for receivers while the picker is open: FCast receivers through NSD (mDNS, done by the
 * system, so no multicast lock), and DLNA renderers through SSDP, searched again every
 * [searchEveryMs]. Both run behind Unreleased.CASTING; with it off [start] does nothing.
 */
class CastDiscovery(
    private val nsd: NsdManager,
    private val scope: CoroutineScope,
    /** The Wi-Fi network, so SSDP and description fetches stay on it under a VPN. */
    private val network: () -> Network?,
    private val searchEveryMs: Long = 10_000,
) {
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val fcast = LinkedHashMap<String, CastDevice>()
    private val dlna = LinkedHashMap<String, CastDevice>()
    private val lock = Any()
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var ssdp: Job? = null

    /** Resolved one at a time: before Android 14, a second resolve while one runs fails outright. */
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (!Unreleased.CASTING) return
        synchronized(lock) {
            if (nsdListener != null) return
            val listener = discoveryListener()
            nsdListener = listener
            runCatching { nsd.discoverServices(FCast.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { Log.w(TAG, "NSD would not start", it) }
            ssdp = scope.launch(Dispatchers.IO) {
                while (true) {
                    searchDlna()
                    delay(searchEveryMs)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            nsdListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            nsdListener = null
            ssdp?.cancel()
            ssdp = null
            toResolve.clear()
            resolving = false
        }
    }

    private suspend fun searchDlna() {
        val wifi = network()
        val found = runCatching {
            DlnaDiscovery.search(
                prepare = { socket -> wifi?.bindSocket(socket) },
                http = LanHttp(socketFactory = wifi?.socketFactory ?: javax.net.SocketFactory.getDefault()),
            )
        }.onFailure { Log.i(TAG, "SSDP search failed: ${it.message}") }.getOrNull() ?: return
        synchronized(lock) {
            dlna.keys.retainAll { it.startsWith("location:") }
            found.forEach { dlna[it.id] = it }
            publish()
        }
    }

    private fun publish() {
        _devices.update { CastDevices.merge(fcast.values, dlna.values) }
    }

    private fun discoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "FCast discovery did not start: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(lock) {
                if (nsdListener == null) return
                toResolve.addLast(info)
                resolveNext()
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(lock) {
                if (fcast.remove("fcast:${info.serviceName}") != null) publish()
            }
        }
    }

    /** Called holding [lock]. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = toResolve.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "Could not resolve ${info.serviceName}: $errorCode")
                    done()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = hostOf(info)
                    if (host != null && info.port > 0) {
                        synchronized(lock) {
                            if (nsdListener != null) {
                                fcast["fcast:${info.serviceName}"] = CastDevices.fcast(info.serviceName, host, info.port, info.attributes)
                                publish()
                            }
                        }
                    }
                    done()
                }

                private fun done() = synchronized(lock) {
                    resolving = false
                    resolveNext()
                }
            })
        }.onFailure {
            resolving = false
            Log.d(TAG, "Resolve refused: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }.firstOrNull()
        } else {
            info.host
        }

    private companion object {
        const val TAG = "CastDiscovery"
    }
}

/** DLNA renderers on the network: an SSDP search, then each one's description. */
object DlnaDiscovery {
    suspend fun search(
        timeoutMs: Long = 3_000,
        prepare: (DatagramSocket) -> Unit = {},
        http: LanHttp = LanHttp(),
        destination: java.net.InetSocketAddress = java.net.InetSocketAddress(Ssdp.MULTICAST_ADDRESS, Ssdp.PORT),
    ): List<CastDevice> {
        val replies = withContext(Dispatchers.IO) {
            Ssdp.search(Ssdp.MEDIA_RENDERER, timeoutMs, destination = destination, prepare = prepare)
        }
        return coroutineScope {
            replies.distinctBy { it.location }.map { reply ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(DESCRIPTION_TIMEOUT_MS) { describe(reply.location, http) }
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }
        }
    }

    /** The renderer at [location] as a [CastDevice], or null when it is not one or cannot be read. */
    fun describe(location: String, http: LanHttp = LanHttp()): CastDevice? {
        val answer = runCatching { http.get(location) }.getOrNull()?.takeIf { it.status == 200 } ?: return null
        val renderer = UpnpDevice.parse(answer.text, location) ?: return null
        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        val host = uri.host?.trim('[', ']') ?: return null
        return CastDevice(
            protocol = CastProtocol.DLNA,
            id = renderer.udn,
            name = renderer.friendlyName,
            host = host,
            port = if (uri.port > 0) uri.port else 80,
            location = location,
            model = listOfNotNull(renderer.manufacturer, renderer.modelName).joinToString(" ").takeIf { it.isNotBlank() },
        )
    }

    private const val DESCRIPTION_TIMEOUT_MS = 6_000L
}
