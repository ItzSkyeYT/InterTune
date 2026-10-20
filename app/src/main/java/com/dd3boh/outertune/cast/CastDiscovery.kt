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
import kotlinx.coroutines.CancellationException
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
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * Receivers typed in by address, for the emulator, whose NAT neither mDNS nor SSDP crosses, and
 * for networks that block multicast.
 *
 * - `fcast 10.0.2.2:46899`, `fcast://10.0.2.2`, or an address alone: an FCast receiver, on 46899
 *   unless a port is given;
 * - `chromecast 192.168.1.21`, `chromecast://192.168.1.21:32187`, or an address alone with the
 *   port 8009: a Chromecast, on 8009 unless a port is given, which a speaker group needs;
 * - `dlna http://10.0.2.2:49152/desc.xml`, or any http url: a DLNA renderer's description.
 */
object CastAddress {
    private val words = mapOf(
        "fcast" to CastProtocol.FCAST,
        "dlna" to CastProtocol.DLNA,
        "chromecast" to CastProtocol.CHROMECAST,
    )

    private val schemes = listOf("fcast://" to CastProtocol.FCAST, "chromecast://" to CastProtocol.CHROMECAST)

    fun parse(text: String): CastDevice? {
        var value = text.trim()
        var protocol: CastProtocol? = null
        // The word alone is the start of an address, not a host called "fcast".
        if (value.lowercase() in words) return null
        val word = value.substringBefore(' ').lowercase()
        if (' ' in value && word in words) {
            protocol = words.getValue(word)
            value = value.substringAfter(' ').trim()
        }
        schemes.firstOrNull { (scheme, _) -> value.startsWith(scheme, ignoreCase = true) }?.let { (scheme, kind) ->
            protocol = kind
            value = value.substring(scheme.length).trimEnd('/')
        }
        if (value.isEmpty()) return null
        if (value.startsWith("http://", ignoreCase = true) && (protocol == null || protocol == CastProtocol.DLNA)) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port > 0) uri.port else 80
            return CastDevice(CastProtocol.DLNA, "location:$value", host, host, port, location = value)
        }
        return when (protocol) {
            CastProtocol.DLNA -> null
            CastProtocol.CHROMECAST -> hostAndPort(value, Chromecast.PORT)?.let { (host, port) -> chromecast(host, port) }
            else -> {
                val (host, port) = hostAndPort(value, FCast.PORT) ?: return null
                // FCast never listens on the Cast port, so an address typed with it means a Chromecast.
                if (protocol == null && port == Chromecast.PORT) chromecast(host, port)
                else CastDevice(CastProtocol.FCAST, "fcast:$host:$port", host, host, port)
            }
        }
    }

    private fun chromecast(host: String, port: Int) =
        CastDevice(CastProtocol.CHROMECAST, "chromecast:$host:$port", host, host, port)

    /** `host`, `host:port`, `[v6]` or `[v6]:port`; a bare IPv6 address has no port. */
    private fun hostAndPort(value: String, defaultPort: Int): Pair<String, Int>? {
        if (value.startsWith("[")) {
            val close = value.indexOf(']').takeIf { it > 1 } ?: return null
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            val port = if (rest.isEmpty()) defaultPort else rest.removePrefix(":").toIntOrNull() ?: return null
            return (host to port).takeIf { port in 1..65535 }
        }
        if (value.count { it == ':' } > 1) return value to defaultPort
        val host = value.substringBefore(':')
        val port = if (':' in value) value.substringAfter(':').toIntOrNull() ?: return null else defaultPort
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) return null
        return (host to port).takeIf { port in 1..65535 }
    }
}

/** How the receivers found by the searches are shown: once each, by name. */
object CastDevices {
    /**
     * [fcast], [dlna] and [chromecast] as one list. A TV running an FCast receiver, or with
     * Chromecast built in, often also answers as a DLNA renderer; it is shown once as that, since
     * both play our songs as they are and report far better than DLNA. A TV with both an FCast
     * receiver and Chromecast is shown both ways: they are two different ways in, and FCast works
     * only while its app is there. Chromecasts are never merged by address, since a speaker group
     * has the address of one of its speakers.
     */
    fun merge(
        fcast: Collection<CastDevice>,
        dlna: Collection<CastDevice>,
        chromecast: Collection<CastDevice> = emptyList(),
    ): List<CastDevice> {
        val betterHosts = (fcast + chromecast).map { it.host.lowercase() }.toSet()
        return (fcast + chromecast + dlna.filter { it.host.lowercase() !in betterHosts })
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

    /**
     * A Chromecast as NSD found it. Its service name is a model and an id (Chromecast-5d1f...), so
     * the name people gave it comes from the TXT record's `fn`, and failing that its model, `md`.
     * `id` is its own id, which stays the same across addresses; `ca` is a set of capability bits,
     * of which 1 is a screen, missing on speakers and speaker groups. The port comes from the SRV
     * record: 8009 for a device, another for a group.
     */
    fun chromecast(serviceName: String, host: InetAddress, port: Int, attributes: Map<String, ByteArray?>): CastDevice {
        fun text(key: String) = attributes[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        val model = text("md")
        val capabilities = text("ca")?.toIntOrNull()
        return CastDevice(
            protocol = CastProtocol.CHROMECAST,
            id = "chromecast:${text("id") ?: serviceName}",
            name = text("fn") ?: model ?: serviceName,
            host = host.hostAddress ?: host.toString(),
            port = port,
            model = model,
            audioOnly = capabilities != null && capabilities and CAPABILITY_VIDEO_OUT == 0,
        )
    }

    private const val CAPABILITY_VIDEO_OUT = 1
}

/**
 * Looks for receivers while the picker is open: FCast receivers and Chromecasts through NSD (mDNS,
 * done by the system, so no multicast lock), and DLNA renderers through SSDP, searched again every
 * [searchEveryMs]. All run behind Unreleased.CASTING; with it off [start] does nothing.
 */
class CastDiscovery(
    private val nsd: NsdManager,
    private val scope: CoroutineScope,
    /** The Wi-Fi network, so SSDP and description fetches stay on it under a VPN. */
    private val network: () -> Network?,
    private val searchEveryMs: Long = 10_000,
    /** The phone's IPv4 address and prefix on the Wi-Fi, for FCast receivers looked for address by address (FCastSweep). */
    private val subnet: () -> Pair<Inet4Address, Int>? = { null },
) {
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    /** Found by NSD, keyed by service name, one map for each service type. */
    private val fcast = LinkedHashMap<String, CastDevice>()
    private val chromecast = LinkedHashMap<String, CastDevice>()
    private val dlna = LinkedHashMap<String, CastDevice>()
    private val lock = Any()
    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var ssdp: Job? = null

    /** FCast receivers that took a connection on their port, keyed by address (FCastSweep). */
    private val swept = LinkedHashMap<String, CastDevice>()
    private var sweep: Job? = null

    /**
     * Resolved one at a time, whichever type they are: before Android 14, a second resolve while
     * one runs fails outright.
     */
    private val toResolve = ArrayDeque<Pair<CastProtocol, NsdServiceInfo>>()
    private var resolving = false

    fun start() {
        if (!Unreleased.CASTING) return
        synchronized(lock) {
            if (nsdListeners.isNotEmpty()) return
            for ((protocol, type) in listOf(CastProtocol.FCAST to FCast.SERVICE_TYPE, CastProtocol.CHROMECAST to Chromecast.SERVICE_TYPE)) {
                val listener = discoveryListener(protocol)
                nsdListeners += listener
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure { Log.w(TAG, "NSD would not start for $type", it) }
            }
            ssdp = scope.launch(Dispatchers.IO) {
                safely("SSDP") {
                    while (true) {
                        searchDlna()
                        delay(searchEveryMs)
                    }
                }
            }
            sweep = scope.launch(Dispatchers.IO) { safely("FCast sweep") { sweepFcast() } }
        }
    }

    /**
     * Runs one of the searches so that nothing it throws reaches the app: a search that fails has
     * found nothing, and the others carry on. A failure let out of the picker's coroutines closes
     * the whole app, as the sweep's did on a network the phone would not let it use (issue #33).
     * Closing the picker cancels the searches, which still ends them.
     */
    private suspend fun safely(search: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$search stopped: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        synchronized(lock) {
            nsdListeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            nsdListeners.clear()
            ssdp?.cancel()
            ssdp = null
            sweep?.cancel()
            sweep = null
            swept.clear()
            toResolve.clear()
            resolving = false
        }
    }

    private suspend fun searchDlna() {
        val wifi = network()
        val found = runCatching {
            DlnaDiscovery.search(
                prepare = { socket -> wifi?.bindSocket(socket) },
                http = DlnaDiscovery.descriptionHttp(wifi?.socketFactory ?: javax.net.SocketFactory.getDefault()),
            )
        }.onFailure {
            // Closing the cast sheet cancels the search; that is the search stopping, not failing,
            // and the cancellation has to carry on up so the coroutine ends.
            if (it is CancellationException) throw it
            Log.i(TAG, "SSDP search failed: ${it.message}")
        }.getOrNull() ?: return
        synchronized(lock) {
            dlna.keys.retainAll { it.startsWith("location:") }
            found.forEach { dlna[it.id] = it }
            publish()
        }
    }

    /**
     * FCast receivers that do not answer the network's search, looked for address by address on
     * the phone's own network while the picker is open (FCastSweep). Each one found is listed at
     * once, under the name it gives itself.
     */
    private suspend fun sweepFcast() {
        val (self, prefix) = subnet() ?: return
        val hosts = FCastSweep.hosts(self, prefix)
        if (hosts.isEmpty()) return
        val factory = network()?.socketFactory ?: javax.net.SocketFactory.getDefault()
        FCastSweep.sweep(hosts, factory) { device ->
            synchronized(lock) {
                if (nsdListeners.isEmpty()) return@sweep
                swept[device.host] = device
                publish()
            }
        }
    }

    private fun publish() {
        // A receiver both the search and the sweep found is listed once, as the search found it.
        val searched = fcast.values
        val sweptOnly = swept.values.filter { s -> searched.none { it.host.equals(s.host, ignoreCase = true) && it.port == s.port } }
        _devices.update { CastDevices.merge(searched + sweptOnly, dlna.values, chromecast.values) }
    }

    private fun found(protocol: CastProtocol) = if (protocol == CastProtocol.CHROMECAST) chromecast else fcast

    private fun discoveryListener(protocol: CastProtocol) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery of $serviceType did not start: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(lock) {
                if (nsdListeners.isEmpty()) return
                toResolve.addLast(protocol to info)
                resolveNext()
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(lock) {
                if (found(protocol).remove(info.serviceName) != null) publish()
            }
        }
    }

    /** Called holding [lock]. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val (protocol, next) = toResolve.removeFirstOrNull() ?: return
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
                        val device = when (protocol) {
                            CastProtocol.CHROMECAST -> CastDevices.chromecast(info.serviceName, host, info.port, info.attributes)
                            else -> CastDevices.fcast(info.serviceName, host, info.port, info.attributes)
                        }
                        synchronized(lock) {
                            if (nsdListeners.isNotEmpty()) {
                                found(protocol)[info.serviceName] = device
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
    /**
     * [http] reads the descriptions, all at once, so a search lasts [timeoutMs] and then as long as
     * the slowest description, which is at most its connect and answer limits put together:
     * [descriptionHttp] in the app.
     */
    suspend fun search(
        timeoutMs: Long = 3_000,
        prepare: (DatagramSocket) -> Unit = {},
        http: LanHttp = descriptionHttp(),
        destination: java.net.InetSocketAddress = java.net.InetSocketAddress(Ssdp.MULTICAST_ADDRESS, Ssdp.PORT),
    ): List<CastDevice> {
        val replies = withContext(Dispatchers.IO) {
            Ssdp.search(Ssdp.MEDIA_RENDERER, timeoutMs, destination = destination, prepare = prepare)
        }
        return coroutineScope {
            replies.distinctBy { it.location }.map { reply ->
                async(Dispatchers.IO) { describe(reply.location, http) }
            }.awaitAll().filterNotNull().distinctBy { it.id }
        }
    }

    /**
     * Six seconds at most for a description while searching, as two for the connection and four
     * for the answer. A timeout around [describe] cannot do it, since a blocking read does not
     * stop for a cancelled coroutine; only the socket's own limits end it. A renderer slower than
     * that is left out of this search and found by the next.
     */
    fun descriptionHttp(socketFactory: javax.net.SocketFactory = javax.net.SocketFactory.getDefault()) =
        LanHttp(socketFactory, connectTimeoutMs = 2_000, answerTimeoutMs = 4_000)

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
}
