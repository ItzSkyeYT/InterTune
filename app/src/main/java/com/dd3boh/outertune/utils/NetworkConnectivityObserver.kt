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

class NetworkConnectivityObserver(context: Context) {
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
            // A different network almost certainly means a different public IP, and switching
            // networks is literally what clears this by hand. Over-clearing costs one request that
            // immediately re-trips; under-clearing costs the user their music.
            Throttle.onNetworkChanged()
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

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}