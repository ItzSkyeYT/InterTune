/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kept addresses and pooled connections go when the network in use changes, not when some
 * other network appears beside it. Read from the source: the callbacks cannot run here.
 */
class NetworkChangeHookTest {
    private val observer = File("src/main/java/com/dd3boh/outertune/utils/NetworkConnectivityObserver.kt").readText()

    @Test
    fun `kept addresses are cleared when the network in use changes, not when another network appears`() {
        assertTrue(observer.contains("registerDefaultNetworkCallback("))
        val anyNetwork = observer.substringAfter("private val networkCallback").substringBefore("private var inUse")
        assertFalse("told of any network appearing", anyNetwork.contains("onNetworkChanged()"))
        val inUse = observer.substringAfter("private val defaultCallback")
        assertTrue(inUse.substringBefore("init {").contains("runCatching { onNetworkChanged() }"))
    }

    @Test
    fun `a refusal just after a network change is not taken for the route's`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val change = service.substringAfter("private fun changeRoute(").substringBefore("private fun noteGivenUp(")
        assertTrue(change.contains("RouteTimes.NETWORK_CHANGE_GRACE_MS"))
    }
}
