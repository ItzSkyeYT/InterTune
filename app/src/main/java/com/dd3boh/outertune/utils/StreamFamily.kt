/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.AddressPolicy
import com.zionhuang.innertube.IpFamily
import com.zionhuang.innertube.keepToFamily
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Which address family a stream is fetched over: the one its address was issued to.
 *
 * A stream url names the address YouTube saw when /player was asked (its ip parameter). Since
 * 3 Oct 2026 /player goes over IPv4 first on a network with both families (see [FamilyChoice]),
 * and the stream was left to the system, which connects over IPv6 wherever it can. So on such a
 * network every song was asked for from one address and fetched from another. YouTube answers a
 * fetch like that with a redirect to another host, and that host decides. Measured on a Pixel 5
 * on 8 Oct 2026: the check and each of the player's requests took two connections over IPv6
 * (302, then the answer), where the same url over IPv4 was answered at once.
 *
 * Whether the second host serves it is YouTube's to decide, and that evening it did not. A fresh
 * install on that phone could not play its first song: VISIONOS answered OK seven times in three
 * minutes and each of its urls was refused with 403, two connections every time, for two visitors
 * YouTube had only just issued. Fresh installs on an emulator that has IPv4 only, on the same
 * line and in the same minutes, played. An hour and a half later the redirected fetch was served
 * again, to new visitors too. A network with one family never takes that path at all.
 *
 * So a stream goes the way its /player request went, and what YouTube makes of a fetch from
 * another address no longer decides whether a song plays. The check of a url, the player and
 * downloads all fetch through [Calls]. They have to agree: a check that passes over one family
 * says nothing of a player that fetches over the other.
 *
 * The same holds for what a web client's address is asked for with, the headers of the page it
 * was issued to ([WebStreamHeaders]): a check made with them says nothing of a player or a
 * download that asks without. So [Calls] puts those on as well, and nobody who fetches a stream
 * has them to remember.
 */
object StreamFamily {
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9A-Fa-f:.]{2,45}""")

    /**
     * The family of the address [url] was issued to, read from its ip parameter, or null when it
     * names none or one that cannot be read. Only the family is taken from it: the address is the
     * listener's own and is never kept or logged.
     */
    fun of(url: String): IpFamily? {
        val query = url.substringAfter('?', "").substringBefore('#')
        val ip = query.split('&').firstOrNull { it.substringBefore('=') == "ip" }
            ?.substringAfter('=', "")
            ?.replace("%3A", ":", ignoreCase = true)
            ?: return null
        return when {
            IPV4.matches(ip) -> IpFamily.V4
            ':' in ip && IPV6.matches(ip) -> IpFamily.V6
            else -> null
        }
    }

    /**
     * The calls of [base], each over the family its address names. One client per family, made
     * when first needed from [base], so they share its connections and its settings. An address
     * that names no family goes to [base] itself, and so does every address when [base] has a
     * proxy: the proxy's own connection decides the family then, as it does for /player.
     *
     * The other family is kept as the way out when the first does not connect: see [AddressPolicy].
     *
     * A call for a web client's address is made with its page's headers, whichever client of
     * these makes it: see [WebStreamHeaders.on].
     */
    /**
     * The client the player streams a song with. A connection left idle is not used again after a
     * few seconds: one that the network dropped without a word takes the request and never
     * answers, and the song sat silent until the read gave up (his phone, 20 Oct 2026: a new
     * connection answered at once each time). A stream that stops coming in is cut after 8 s,
     * so the player asks again instead of waiting.
     */
    fun streamClient(proxy: java.net.Proxy?): OkHttpClient = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(4, 5, java.util.concurrent.TimeUnit.SECONDS))
        // A stream made to stop or be refused on purpose, in a debug build only: see FaultInjection.
        .apply { if (FaultInjection.enabled) addInterceptor(FaultInjection.interceptor(FaultInjection.Target.STREAM)) }
        .build()

    class Calls(private val base: OkHttpClient, private val system: Dns = Dns.SYSTEM) : Call.Factory {
        private val overV4 by lazy { keepingTo(IpFamily.V4) }
        private val overV6 by lazy { keepingTo(IpFamily.V6) }

        private fun keepingTo(family: IpFamily): OkHttpClient =
            base.newBuilder().apply(keepToFamily(AddressPolicy(family), system)).build()

        fun clientFor(url: String): OkHttpClient {
            if (base.proxy != null) return base
            return when (of(url)) {
                IpFamily.V4 -> overV4
                IpFamily.V6 -> overV6
                null -> base
            }
        }

        /** The calls made and not yet given up, held weakly: a finished call is let go. */
        private val made = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Call, Boolean>())

        /**
         * The reads of a song made beside these calls but not through them: SABR's, whose requests
         * go out on a client of its own. Cut with the calls by [cancelInFlight] while they are
         * open, each by the [java.io.Closeable] it gave. Out of reach, a SABR read stayed blocked
         * through the stall watch's first step, its address was forgotten at the second while it
         * still waited, and the song was given up at the third.
         */
        private val alongside = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.Closeable>()

        override fun newCall(request: Request): Call =
            clientFor(request.url.toString()).newCall(WebStreamHeaders.on(request))
                .also { synchronized(made) { made += it } }

        /** [cut] is called by [cancelInFlight] until the handle returned is closed, which a read does at its own close. */
        fun cutAlongside(cut: java.io.Closeable): java.io.Closeable {
            alongside += cut
            return java.io.Closeable { alongside -= cut }
        }

        /**
         * Cancels every call still out, so a read blocked on a connection that went silent throws
         * at once and the player asks again on a new one, from the same byte, and cuts the reads
         * made beside them ([cutAlongside]) the same way. Call.cancel is safe from any thread, and
         * does nothing to a call already done. For the stall watch, on the player's own calls
         * only. Returns how many were cancelled or cut.
         */
        fun cancelInFlight(): Int {
            val calls = synchronized(made) {
                val out = made.filterNot { it.isCanceled() }
                out.forEach { it.cancel() }
                made.clear()
                out.size
            }
            val reads = alongside.toList()
            alongside.removeAll(reads.toSet())
            reads.forEach { runCatching { it.close() } }
            return calls + reads.size
        }
    }
}
