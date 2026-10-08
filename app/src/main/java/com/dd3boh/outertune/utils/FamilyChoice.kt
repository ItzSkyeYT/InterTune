/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.AddressPolicy
import com.zionhuang.innertube.IpFamily
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which address family /player is asked over, and what to remember about how that went.
 *
 * YouTube can distrust one family of a connection and not the other. Seen on 3 Oct 2026 on one
 * French ISP's dual-stack Wi-Fi: every client got "Sign in to confirm you're not a bot" for every
 * song over IPv6, and the same request over IPv4 was served. Android connects over IPv6 first, so
 * the app told people YouTube had stopped serving their connection while half of it was fine.
 *
 * So on a network with both families, /player goes over IPv4 first, and a chain refused with the
 * bot check is asked once more over the other family, so the day it is IPv4 that YouTube distrusts,
 * IPv6 rescues it. Whichever family worked is remembered for that network, so the songs after it
 * go straight there instead of being refused first every time.
 *
 * Only /player is asked this way. Search and browse connect as before: they were never refused.
 * The stream follows by itself, over the family its address was issued to: see [StreamFamily]. It
 * was first left to the system, because a stream url issued over IPv4 did play over IPv6 (checked
 * live on 3 Oct: HEAD 200, and a ranged GET past the first megabyte answered 206 after one
 * redirect). On 8 Oct that redirected fetch was refused for a new install, every time for three
 * minutes, so it is no longer counted on.
 */
object FamilyChoice {
    /** How long a family that worked stays the first one asked over on that network. */
    const val REMEMBER_MS = 6 * 60 * 60 * 1000L

    /**
     * After the second family failed too, how long before trying it again. Both refused means the
     * whole connection is distrusted, and asking twice for every song would double the requests to
     * a network YouTube is already refusing. The same span as StreamCheck.NEW_VISITOR_RETRY_MS.
     */
    const val RETRY_AFTER_BOTH_REFUSED_MS = 10 * 60 * 1000L

    /** What the network in use offers. [key] tells networks apart, and changes on reconnecting. */
    data class Network(val key: Long, val v4: Boolean, val v6: Boolean) {
        val dualStack: Boolean get() = v4 && v6
    }

    /** What is known about one network. Times are elapsed realtime. */
    data class Memory(
        val networkKey: Long,
        val worked: IpFamily? = null,
        val workedAt: Long = 0L,
        val bothRefusedAt: Long? = null,
    )

    /** One try over one family: [ok] that it succeeded at all, [refused] that it got the bot check. */
    class Attempt<T>(val value: T, val ok: Boolean, val refused: Boolean)

    /**
     * What [resolve] decided. [chosen] is the try whose answer is used and whose verdict the
     * throttle hears; [families] the families asked over, in order, null meaning the system's choice.
     */
    class Outcome<T>(val chosen: Attempt<T>, val memory: Memory?, val families: List<IpFamily?>)

    /**
     * The network's families from its own addresses (LinkProperties.linkAddresses).
     *
     * Only addresses that reach the internet count: a link-local or unique local IPv6 address is on
     * every Wi-Fi with IPv6 switched off upstream. On an IPv6-only network with 464XLAT, IPv4 goes
     * through the CLAT, whose address is on a stacked link that this list does not include, so
     * such a network counts as IPv6 only and keeps connecting the way it always has.
     */
    fun networkOf(key: Long, addresses: List<InetAddress>) = Network(
        key = key,
        v4 = addresses.any { it is Inet4Address && it.reachesInternet() && !it.isClat() },
        v6 = addresses.any { it is Inet6Address && it.reachesInternet() && !it.isUniqueLocal() },
    )

    private fun InetAddress.reachesInternet() =
        !isLoopbackAddress && !isLinkLocalAddress && !isAnyLocalAddress && !isMulticastAddress && !isSiteLocalIpv6()

    private fun InetAddress.isSiteLocalIpv6() = this is Inet6Address && isSiteLocalAddress

    /** fc00::/7. */
    private fun Inet6Address.isUniqueLocal() = (address[0].toInt() and 0xfe) == 0xfc

    /** 192.0.0.0/29, which 464XLAT uses for the CLAT's own IPv4 address. */
    private fun Inet4Address.isClat(): Boolean {
        val b = address
        return b[0] == 192.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && (b[3].toInt() and 0xf8) == 0
    }

    /**
     * The family to ask over first, or null to leave it to the system.
     *
     * Null when the network has one family only (nothing to choose) and when nothing is known about
     * it. On a dual-stack network, the family that last worked there if that was recent, else IPv4.
     */
    fun first(net: Network?, memory: Memory?, now: Long): IpFamily? {
        if (net == null || !net.dualStack) return null
        val remembered = memory
            ?.takeIf { it.networkKey == net.key && it.worked != null && now - it.workedAt < REMEMBER_MS }
            ?.worked
        return remembered ?: IpFamily.V4
    }

    /**
     * The family to ask over a second time, or null not to.
     *
     * Only after the bot check, only on a network that has the other family, and not again for a
     * while after the other family failed too.
     */
    fun retryOver(net: Network?, tried: IpFamily?, refused: Boolean, memory: Memory?, now: Long): IpFamily? {
        if (tried == null || !refused || net == null || !net.dualStack) return null
        val sinceBothRefused = memory?.takeIf { it.networkKey == net.key }?.bothRefusedAt?.let { now - it }
        if (sinceBothRefused != null && sinceBothRefused < RETRY_AFTER_BOTH_REFUSED_MS) return null
        return tried.other
    }

    fun afterWorked(net: Network, family: IpFamily, now: Long) = Memory(net.key, family, now)

    /** Keeps what worked before on this network: both refused now does not make it wrong later. */
    fun afterBothRefused(memory: Memory?, net: Network, now: Long): Memory =
        (memory?.takeIf { it.networkKey == net.key } ?: Memory(net.key)).copy(bothRefusedAt = now)

    /**
     * Asks once over [first]'s family, and over the other when that was refused and [retryOver]
     * allows it.
     *
     * The first try may still fall back to the other family when an address of its own does not
     * connect. Which family answered is not recorded: [first] is remembered either way.
     * The second keeps to its family: falling back would only ask the family that just refused.
     *
     * When the second try works, it is the answer and the first refusal is never reported, so the
     * throttle does not back off over a connection that is serving music. When it fails too, the
     * first try is the answer, its refusal is what the throttle hears, and it backs off.
     */
    suspend fun <T> resolve(
        net: Network?,
        memory: Memory?,
        now: Long,
        attempt: suspend (AddressPolicy?) -> Attempt<T>,
    ): Outcome<T> {
        val first = first(net, memory, now)
        val firstTry = attempt(first?.let { AddressPolicy(it) })
        val worked = firstTry.ok && !firstTry.refused
        val other = retryOver(net, first, firstTry.refused, memory, now)
        if (other == null || net == null) {
            val remembered = if (worked && first != null && net != null) afterWorked(net, first, now) else memory
            return Outcome(firstTry, remembered, listOf(first))
        }
        val secondTry = attempt(AddressPolicy(other, only = true))
        return if (secondTry.ok && !secondTry.refused) {
            Outcome(secondTry, afterWorked(net, other, now), listOf(first, other))
        } else {
            Outcome(firstTry, afterBothRefused(memory, net, now), listOf(first, other))
        }
    }
}
