package com.zionhuang.innertube

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The two ways a request can reach YouTube.
 *
 * YouTube judges an address, and it can judge the two families of one connection differently: on
 * one French ISP's dual-stack line every /player request over IPv6 got "Sign in to confirm you're
 * not a bot" while the identical request over IPv4 was answered with 23 formats. Android connects
 * over IPv6 first wherever it can, so the app was refused while the line itself was fine.
 */
enum class IpFamily {
    V4, V6;

    val other: IpFamily get() = if (this == V4) V6 else V4

    val label: String get() = if (this == V4) "IPv4" else "IPv6"

    companion object {
        fun of(address: InetAddress): IpFamily = if (address is Inet6Address) V6 else V4
    }
}

/**
 * Which addresses a connection may use, and in what order.
 *
 * [only] leaves the other family out altogether. That is for asking a second time over the other
 * family on purpose: falling back to the family that has just refused would only repeat the
 * refusal, at the cost of another request to an address YouTube is already wary of.
 */
data class AddressPolicy(val first: IpFamily, val only: Boolean = false) {
    fun order(addresses: List<InetAddress>): List<InetAddress> {
        val (preferred, rest) = addresses.partition { IpFamily.of(it) == first }
        return if (only) preferred else preferred + rest
    }
}

/** The system's answer, reordered (or narrowed) by [policy]. */
class FamilyDns(
    private val policy: AddressPolicy,
    private val system: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val ordered = policy.order(system.lookup(hostname))
        if (ordered.isEmpty()) throw UnknownHostException("$hostname has no ${policy.first.label} address")
        return ordered
    }
}

/**
 * OkHttp settings that make a client keep to [policy].
 *
 * The order alone is not enough. OkHttp 5 races connections by default ("fast fallback"), and
 * before racing it reorders the addresses so that an IPv6 one goes first, whatever order the Dns
 * gave. IPv6 then wins whenever it connects within 250 ms, which on a healthy line is always. With
 * the race off, the addresses are tried one after another in the order given, so a family that
 * cannot connect at all (no route, an IPv6-only network) still falls through to the next.
 */
fun keepToFamily(policy: AddressPolicy, system: Dns = Dns.SYSTEM): OkHttpClient.Builder.() -> Unit = {
    dns(FamilyDns(policy, system))
    fastFallback(false)
}
