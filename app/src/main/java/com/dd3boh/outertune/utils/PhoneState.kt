/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

/**
 * What the phone's connection and battery are like, for Report a problem: whether it is on Wi-Fi
 * or mobile data, metered, behind a VPN or Data Saver, how fast Android reckons the link is, and
 * whether the battery saver or a background restriction holds the app back. Nothing that names a
 * network, an address, a place or a person: a report can be posted in public.
 *
 * Why: a report of 26 Oct 2026 showed every stream coming in slower than its song, and nothing in
 * it could tell a weak line from YouTube slowing one address. [describe] is logged when the player
 * starts and at every change of network, so a report of any run carries it.
 */
data class PhoneState(
    /** "Wi-Fi", "mobile data", "Ethernet", or null with no network at all. */
    val network: String?,
    val vpn: Boolean = false,
    val metered: Boolean = false,
    /** Android found the internet behind it (NET_CAPABILITY_VALIDATED). */
    val validated: Boolean = true,
    /** Android's own estimate of the link, in kbps, when it has one. */
    val downKbps: Int? = null,
    val upKbps: Int? = null,
    val dataSaver: Boolean = false,
    val batterySaver: Boolean = false,
    /** Null where the phone cannot say (before Android 9 for the restriction). */
    val backgroundRestricted: Boolean? = null,
    val optimisationExempt: Boolean? = null,
) {
    /** In a line: "Wi-Fi, not metered, about 45 Mbps down and 12 Mbps up, no VPN, Data Saver off; battery saver off, ...". */
    fun describe(): String = buildString {
        if (network == null) append("no network") else {
            append(network)
            append(if (metered) ", metered" else ", not metered")
            if (!validated) append(", no internet behind it")
            speed(downKbps, upKbps)?.let { append(", ").append(it) }
            append(if (vpn) ", a VPN on" else ", no VPN")
        }
        append(if (dataSaver) ", Data Saver on" else ", Data Saver off")
        append("; battery saver ").append(if (batterySaver) "on" else "off")
        backgroundRestricted?.let { append(", ").append(if (it) "restricted in the background" else "not restricted in the background") }
        optimisationExempt?.let { append(", battery optimisation ").append(if (it) "off for InterTune" else "on for InterTune") }
    }

    companion object {
        /** "about 120 kbps" under a megabit, "about 45 Mbps" from one. */
        fun rate(kbps: Int): String = if (kbps < 1000) "about $kbps kbps" else "about ${(kbps + 500) / 1000} Mbps"

        private fun speed(down: Int?, up: Int?): String? = when {
            down != null && down > 0 && up != null && up > 0 -> "${rate(down)} down and ${rate(up).removePrefix("about ")} up"
            down != null && down > 0 -> "${rate(down)} down"
            else -> null
        }

        /** The phone as it is now. Never throws: what cannot be read is left out. */
        fun read(context: Context): PhoneState {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = runCatching { connectivity?.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            val network = caps?.let {
                when {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth tethering"
                    else -> "another network"
                }
            }
            val power = context.getSystemService(PowerManager::class.java)
            val activity = context.getSystemService(ActivityManager::class.java)
            return PhoneState(
                network = network,
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != false,
                downKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
                upKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
                dataSaver = runCatching { connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED }.getOrNull() == true,
                batterySaver = runCatching { power?.isPowerSaveMode }.getOrNull() == true,
                backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { activity?.isBackgroundRestricted }.getOrNull() else null,
                optimisationExempt = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            )
        }
    }
}
