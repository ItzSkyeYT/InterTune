/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class NetworkConnectivityObserver(
    context: Context,
    /** Told when the network in use changes to another one, as the throttle is. */
    private val onNetworkChanged: () -> Unit = {},
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = Channel<Boolean>(Channel.CONFLATED)
    val networkStatus = _networkStatus.receiveAsFlow()

    /** Guarded by itself: the callbacks arrive on a platform thread, the set is read from ours. */
    private val availableNetworks = mutableSetOf<Network>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(availableNetworks) { availableNetworks.add(network) }
            _networkStatus.trySend(true)
        }

        override fun onLost(network: Network) {
            // This callback sees every network with internet, not only the one in use, so the
            // mobile link being torn down after Wi-Fi wins arrives here as a loss with no gain to
            // follow. Reporting offline for that left the app treating a healthy connection as
            // dead until the next real change, and sent every playback error, even a plain 403,
            // down the wait-to-reconnect path.
            val remaining = synchronized(availableNetworks) {
                availableNetworks.remove(network)
                availableNetworks.size
            }
            _networkStatus.trySend(remaining > 0)
        }
    }

    /**
     * The network in use. The callback above sees every network with internet: mobile data
     * coming up beside Wi-Fi, or a VPN, cleared every kept address while nothing the app used
     * had changed, and leaving Wi-Fi for mobile data that was already up changed nothing at all.
     * Wi-Fi coming up was also told before it became the one in use.
     */
    private var inUse: Network? = null

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val before = synchronized(this) { inUse.also { inUse = network } }
            // The first is the network already in use when this was registered: nothing changed.
            if (before == null || before == network) return
            // A different network almost certainly means a different public IP, and switching
            // networks is literally what clears this by hand. Over-clearing costs one request that
            // immediately re-trips; under-clearing costs the user their music.
            Throttle.onNetworkChanged()
            NetworkPace.forget()
            runCatching { onNetworkChanged() }
        }
    }

    init {
        // Said at once: the callbacks say nothing at all while there is no network, so a start
        // with none was taken for online, and the wait for the network gave up and never came
        // back when it arrived.
        _networkStatus.trySend(onlineNow())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        runCatching { connectivityManager.registerDefaultNetworkCallback(defaultCallback) }
    }

    private fun onlineNow(): Boolean = runCatching {
        val active = connectivityManager.activeNetwork ?: return@runCatching false
        connectivityManager.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(defaultCallback) }
        connectivityManager.unregisterNetworkCallback(networkCallback)
        // Ends the collection too, which would otherwise wait on this channel for good.
        _networkStatus.close()
    }
}