/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether a VPN is on, so a note about YouTube not being reached names a VPN only then. */
object VpnState {
    fun active(context: Context): Boolean = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
}
