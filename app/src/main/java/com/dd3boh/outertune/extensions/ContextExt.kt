package com.dd3boh.outertune.extensions

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.TabletUiKey
import com.dd3boh.outertune.constants.YtmSyncKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.innertube.utils.parseCookieString

fun Context.isAutoSyncEnabled(): Boolean {
    return dataStore.get(YtmSyncKey, true) && isUserLoggedIn()
}

fun Context.isUserLoggedIn(): Boolean {
    val cookie = dataStore.get(InnerTubeCookieKey, "")
    return "SAPISID" in parseCookieString(cookie)
}

fun Context.isInternetConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
}

fun Context.supportsWideScreen() : Boolean {
    val config = resources.configuration
    return config.screenWidthDp >= 600
}

fun Context.tabMode(): Boolean {
    val config = resources.configuration
    return tabMode(config.smallestScreenWidthDp, config.orientation == Configuration.ORIENTATION_LANDSCAPE, dataStore[TabletUiKey])
}

/**
 * The tablet layout: a screen at least 600dp across its short side, or one the setting
 * ([forced], null when never set) puts in it, and only on its side. The player's
 * rememberTabMode asks the same as the setting changes.
 */
fun tabMode(smallestWidthDp: Int, landscape: Boolean, forced: Boolean?): Boolean {
    val isTablet = smallestWidthDp >= 600
    return (isTablet || (forced ?: isTablet)) && landscape
}

fun Context.isPowerSaver(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isPowerSaveMode
}